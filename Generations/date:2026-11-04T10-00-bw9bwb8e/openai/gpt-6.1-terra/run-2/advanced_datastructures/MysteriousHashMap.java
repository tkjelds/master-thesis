@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_COUNT = 16;
    private static final int STRIPE_BITS = 4;
    private static final int INITIAL_BUCKET_COUNT = 16;
    private static final int MAX_BUCKET_COUNT = 1 << 30;

    private static final class Node<K, V> {
        final int hash;
        final K key;
        final V value;
        final Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final class Bucket<K, V> {
        volatile Node<K, V> head;
    }

    private static final class Stripe<K, V> {
        volatile Bucket<K, V>[] buckets;
        long size;

        Stripe() {
            buckets = newBuckets(INITIAL_BUCKET_COUNT);
        }
    }

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        Stripe<K, V>[] array = (Stripe<K, V>[]) new Stripe<?, ?>[STRIPE_COUNT];
        for (int i = 0; i < array.length; i++) {
            array[i] = new Stripe<K, V>();
        }
        stripes = array;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Bucket<K, V>[] newBuckets(int length) {
        Bucket<K, V>[] array = (Bucket<K, V>[]) new Bucket<?, ?>[length];
        for (int i = 0; i < length; i++) {
            array[i] = new Bucket<K, V>();
        }
        return array;
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private Stripe<K, V> stripeFor(int hash) {
        return stripes[hash & (STRIPE_COUNT - 1)];
    }

    private static int bucketIndex(int hash, int length) {
        return (hash >>> STRIPE_BITS) & (length - 1);
    }

    private static <K, V> Node<K, V> find(Node<K, V> node, int hash, K key) {
        while (node != null) {
            if (node.hash == hash && (node.key == key || key.equals(node.key))) {
                return node;
            }
            node = node.next;
        }
        return null;
    }

    public boolean containsKey(K k) {
        return get(k) != null;
    }

    public V get(K k) {
        if (k == null) {
            throw new NullPointerException();
        }
        int h = hash(k);
        Stripe<K, V> stripe = stripeFor(h);
        Bucket<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = find(buckets[bucketIndex(h, buckets.length)].head, h, k);
        return node == null ? null : node.value;
    }

    public V put(K k, V v) {
        if (k == null || v == null) {
            throw new NullPointerException();
        }
        int h = hash(k);
        Stripe<K, V> stripe = stripeFor(h);
        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            Bucket<K, V> bucket = buckets[bucketIndex(h, buckets.length)];
            Node<K, V> oldHead = bucket.head;
            Node<K, V> found = find(oldHead, h, k);

            if (found != null) {
                Node<K, V> newHead = null;
                for (Node<K, V> node = oldHead; node != null; node = node.next) {
                    V value = node == found ? v : node.value;
                    newHead = new Node<K, V>(node.hash, node.key, value, newHead);
                }
                bucket.head = newHead;
                return found.value;
            }

            if (buckets.length < MAX_BUCKET_COUNT
                    && stripe.size + 1 > (long) buckets.length * 3 / 4) {
                buckets = resize(stripe, buckets);
                bucket = buckets[bucketIndex(h, buckets.length)];
            }

            bucket.head = new Node<K, V>(h, k, v, bucket.head);
            stripe.size++;
            return null;
        }
    }

    private static <K, V> Bucket<K, V>[] resize(
            Stripe<K, V> stripe, Bucket<K, V>[] oldBuckets) {
        Bucket<K, V>[] newBuckets = newBuckets(oldBuckets.length * 2);
        for (int i = 0; i < oldBuckets.length; i++) {
            for (Node<K, V> node = oldBuckets[i].head;
                    node != null; node = node.next) {
                Bucket<K, V> bucket =
                        newBuckets[bucketIndex(node.hash, newBuckets.length)];
                bucket.head = new Node<K, V>(
                        node.hash, node.key, node.value, bucket.head);
            }
        }
        stripe.buckets = newBuckets;
        return newBuckets;
    }

    public V remove(K k) {
        if (k == null) {
            throw new NullPointerException();
        }
        int h = hash(k);
        Stripe<K, V> stripe = stripeFor(h);
        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            Bucket<K, V> bucket = buckets[bucketIndex(h, buckets.length)];
            Node<K, V> oldHead = bucket.head;
            Node<K, V> found = find(oldHead, h, k);
            if (found == null) {
                return null;
            }

            Node<K, V> newHead = null;
            for (Node<K, V> node = oldHead; node != null; node = node.next) {
                if (node != found) {
                    newHead = new Node<K, V>(
                            node.hash, node.key, node.value, newHead);
                }
            }
            bucket.head = newHead;
            stripe.size--;
            return found.value;
        }
    }

    public int size() {
        long total = 0;
        for (int i = 0; i < stripes.length; i++) {
            Stripe<K, V> stripe = stripes[i];
            synchronized (stripe) {
                total += stripe.size;
            }
            if (total >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }
        for (int i = 0; i < stripes.length; i++) {
            Bucket<K, V>[] buckets = stripes[i].buckets;
            for (int j = 0; j < buckets.length; j++) {
                for (Node<K, V> node = buckets[j].head;
                        node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }
}
