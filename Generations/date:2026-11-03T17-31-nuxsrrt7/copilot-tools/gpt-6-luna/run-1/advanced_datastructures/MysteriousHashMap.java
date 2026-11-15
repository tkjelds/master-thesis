@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_COUNT = 16;
    private static final int STRIPE_BITS = 4;
    private static final int INITIAL_BUCKET_COUNT = 8;
    private static final int MAX_BUCKET_COUNT = 1 << 30;

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe<?, ?>[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new Stripe<K, V>(INITIAL_BUCKET_COUNT);
        }
    }

    public boolean containsKey(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        Node<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];

        while (node != null) {
            if (node.hash == hash && key.equals(node.key)) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    public V get(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        Node<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];

        while (node != null) {
            if (node.hash == hash && key.equals(node.key)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V put(K key, V value) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];

        synchronized (stripe) {
            Node<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> prefix = null;
            Node<K, V> node = buckets[index];

            while (node != null) {
                if (node.hash == hash && key.equals(node.key)) {
                    V previousValue = node.value;
                    Node<K, V> updated = new Node<K, V>(node.hash, node.key, value, node.next);
                    for (Node<K, V> p = prefix; p != null; p = p.next) {
                        updated = new Node<K, V>(p.hash, p.key, p.value, updated);
                    }

                    Node<K, V>[] updatedBuckets = copyBuckets(buckets);
                    updatedBuckets[index] = updated;
                    stripe.buckets = updatedBuckets;
                    return previousValue;
                }
                prefix = new Node<K, V>(node.hash, node.key, node.value, prefix);
                node = node.next;
            }

            Node<K, V>[] updatedBuckets;
            if (stripe.size >= threshold(buckets.length) && buckets.length < MAX_BUCKET_COUNT) {
                updatedBuckets = rehash(buckets, buckets.length << 1);
            } else {
                updatedBuckets = copyBuckets(buckets);
            }

            int updatedIndex = bucketIndex(hash, updatedBuckets.length);
            updatedBuckets[updatedIndex] =
                    new Node<K, V>(hash, key, value, updatedBuckets[updatedIndex]);
            stripe.buckets = updatedBuckets;
            stripe.size++;
            return null;
        }
    }

    public V remove(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];

        synchronized (stripe) {
            Node<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> prefix = null;
            Node<K, V> node = buckets[index];

            while (node != null) {
                if (node.hash == hash && key.equals(node.key)) {
                    V previousValue = node.value;
                    Node<K, V> updated = node.next;
                    for (Node<K, V> p = prefix; p != null; p = p.next) {
                        updated = new Node<K, V>(p.hash, p.key, p.value, updated);
                    }

                    Node<K, V>[] updatedBuckets = copyBuckets(buckets);
                    updatedBuckets[index] = updated;
                    stripe.buckets = updatedBuckets;
                    stripe.size--;
                    return previousValue;
                }
                prefix = new Node<K, V>(node.hash, node.key, node.value, prefix);
                node = node.next;
            }
            return null;
        }
    }

    public int size() {
        long total = 0;
        for (Stripe<K, V> stripe : stripes) {
            total += stripe.size;
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }

        for (Stripe<K, V> stripe : stripes) {
            Node<K, V>[] buckets = stripe.buckets;
            for (Node<K, V> bucket : buckets) {
                for (Node<K, V> node = bucket; node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }

    private static int hash(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int bucketIndex(int hash, int bucketCount) {
        return (hash >>> STRIPE_BITS) & (bucketCount - 1);
    }

    private static int threshold(int bucketCount) {
        return bucketCount - (bucketCount >>> 2);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newBucketArray(int capacity) {
        return (Node<K, V>[]) new Node<?, ?>[capacity];
    }

    private static <K, V> Node<K, V>[] copyBuckets(Node<K, V>[] source) {
        Node<K, V>[] copy = newBucketArray(source.length);
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static <K, V> Node<K, V>[] rehash(Node<K, V>[] source, int capacity) {
        Node<K, V>[] resized = newBucketArray(capacity);
        for (Node<K, V> bucket : source) {
            for (Node<K, V> node = bucket; node != null; node = node.next) {
                int index = bucketIndex(node.hash, capacity);
                resized[index] = new Node<K, V>(node.hash, node.key, node.value, resized[index]);
            }
        }
        return resized;
    }

    private static final class Stripe<K, V> {
        private volatile Node<K, V>[] buckets;
        private volatile int size;

        private Stripe(int bucketCount) {
            buckets = newBucketArray(bucketCount);
        }
    }

    private static final class Node<K, V> {
        private final int hash;
        private final K key;
        private final V value;
        private final Node<K, V> next;

        private Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
