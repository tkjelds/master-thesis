@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int DEFAULT_STRIPE_COUNT = 16;
    private static final int DEFAULT_BUCKET_CAPACITY = 16;

    private final Stripe<K, V>[] stripes;

    public MysteriousHashMap() {
        this(DEFAULT_STRIPE_COUNT, DEFAULT_BUCKET_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public MysteriousHashMap(int stripeCount, int initialBucketCapacity) {
        if (stripeCount <= 0 || initialBucketCapacity <= 0) {
            throw new IllegalArgumentException("Stripe count and bucket capacity must be positive");
        }

        stripes = (Stripe<K, V>[]) new Stripe[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Stripe<>(initialBucketCapacity);
        }
    }

    public boolean containsKey(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripeFor(hash);
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
        Stripe<K, V> stripe = stripeFor(hash);
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
        if (value == null) {
            throw new NullPointerException();
        }

        int hash = hash(key);
        Stripe<K, V> stripe = stripeFor(hash);

        synchronized (stripe) {
            Node<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> head = buckets[index];

            for (Node<K, V> node = head; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    V previousValue = node.value;
                    Node<K, V>[] updatedBuckets = buckets.clone();
                    updatedBuckets[index] = new Node<>(hash, key, value, copyExcept(head, hash, key));
                    stripe.buckets = updatedBuckets;
                    return previousValue;
                }
            }

            int newSize = stripe.size + 1;
            Node<K, V>[] updatedBuckets = buckets;
            if (newSize > threshold(buckets.length)
                    && buckets.length <= (Integer.MAX_VALUE >>> 1)) {
                updatedBuckets = rehash(buckets, buckets.length << 1);
            } else {
                updatedBuckets = buckets.clone();
            }

            int updatedIndex = bucketIndex(hash, updatedBuckets.length);
            updatedBuckets[updatedIndex] =
                    new Node<>(hash, key, value, updatedBuckets[updatedIndex]);
            stripe.buckets = updatedBuckets;
            stripe.size = newSize;
            return null;
        }
    }

    public V remove(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripeFor(hash);

        synchronized (stripe) {
            Node<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> head = buckets[index];

            for (Node<K, V> node = head; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    Node<K, V>[] updatedBuckets = buckets.clone();
                    updatedBuckets[index] = copyExcept(head, hash, key);
                    stripe.buckets = updatedBuckets;
                    stripe.size--;
                    return node.value;
                }
            }
            return null;
        }
    }

    public int size() {
        long total = 0;
        for (Stripe<K, V> stripe : stripes) {
            synchronized (stripe) {
                total += stripe.size;
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }

        for (Stripe<K, V> stripe : stripes) {
            Node<K, V>[] buckets = stripe.buckets;
            for (Node<K, V> head : buckets) {
                for (Node<K, V> node = head; node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }

    private Stripe<K, V> stripeFor(int hash) {
        return stripes[hash % stripes.length];
    }

    private static int hash(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
        int value = key.hashCode();
        return value ^ (value >>> 16);
    }

    private static int bucketIndex(int hash, int capacity) {
        return (hash & 0x7fffffff) % capacity;
    }

    private static int threshold(int capacity) {
        return capacity - (capacity >>> 2);
    }

    private static <K, V> Node<K, V> copyExcept(
            Node<K, V> head, int hash, K key) {
        Node<K, V> result = null;
        for (Node<K, V> node = head; node != null; node = node.next) {
            if (node.hash != hash || !key.equals(node.key)) {
                result = new Node<>(node.hash, node.key, node.value, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newBucketArray(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }

    private static <K, V> Node<K, V>[] rehash(
            Node<K, V>[] buckets, int newCapacity) {
        Node<K, V>[] updatedBuckets = newBucketArray(newCapacity);
        for (Node<K, V> head : buckets) {
            for (Node<K, V> node = head; node != null; node = node.next) {
                int index = bucketIndex(node.hash, newCapacity);
                updatedBuckets[index] =
                        new Node<>(node.hash, node.key, node.value, updatedBuckets[index]);
            }
        }
        return updatedBuckets;
    }

    private static final class Stripe<K, V> {
        private volatile Node<K, V>[] buckets;
        private int size;

        private Stripe(int initialBucketCapacity) {
            buckets = newBucketArray(initialBucketCapacity);
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
