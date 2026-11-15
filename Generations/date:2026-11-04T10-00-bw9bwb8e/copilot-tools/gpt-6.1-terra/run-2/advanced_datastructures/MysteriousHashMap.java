@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    // Defined here because collection and functional-interface libraries are disallowed.
    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_BITS = 4;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int INITIAL_BUCKETS = 16;
    private static final int MAX_BUCKETS = 1 << 30;

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
            buckets = newBuckets(INITIAL_BUCKETS);
        }
    }

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe<?, ?>[STRIPE_COUNT];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe<K, V>();
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Bucket<K, V>[] newBuckets(int capacity) {
        Bucket<K, V>[] buckets = (Bucket<K, V>[]) new Bucket<?, ?>[capacity];
        for (int i = 0; i < capacity; i++) {
            buckets[i] = new Bucket<K, V>();
        }
        return buckets;
    }

    private static int hash(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private Stripe<K, V> stripe(int hash) {
        return stripes[hash & (STRIPE_COUNT - 1)];
    }

    private static int bucketIndex(int hash, int length) {
        return (hash >>> STRIPE_BITS) & (length - 1);
    }

    private static <K, V> Node<K, V> find(Node<K, V> node, K key, int hash) {
        for (; node != null; node = node.next) {
            if (node.hash == hash && (node.key == key || key.equals(node.key))) {
                return node;
            }
        }
        return null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public V get(K key) {
        int hash = hash(key);
        Bucket<K, V>[] buckets = stripe(hash).buckets;
        Node<K, V> node = find(buckets[bucketIndex(hash, buckets.length)].head, key, hash);
        return node == null ? null : node.value;
    }

    // Writers wait for the relevant stripe monitor; the map does not wait for
    // space or for entries to appear.
    public V put(K key, V value) {
        int hash = hash(key);
        if (value == null) {
            throw new NullPointerException();
        }

        Stripe<K, V> stripe = stripe(hash);
        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            Bucket<K, V> bucket = buckets[bucketIndex(hash, buckets.length)];
            Node<K, V> oldHead = bucket.head;
            Node<K, V> oldNode = find(oldHead, key, hash);

            if (oldNode == null) {
                bucket.head = new Node<K, V>(hash, key, value, oldHead);
                stripe.size++;
                if (buckets.length < MAX_BUCKETS
                        && stripe.size > buckets.length - (buckets.length >>> 2)) {
                    resize(stripe, buckets);
                }
                return null;
            }

            Node<K, V> newHead = null;
            for (Node<K, V> node = oldHead; node != null; node = node.next) {
                newHead = new Node<K, V>(node.hash, node.key,
                        node == oldNode ? value : node.value, newHead);
            }
            bucket.head = newHead;
            return oldNode.value;
        }
    }

    // Called with the stripe locked. Old buckets are never modified after
    // the new array is published, so readers can finish using the old array.
    private static <K, V> void resize(Stripe<K, V> stripe, Bucket<K, V>[] oldBuckets) {
        Bucket<K, V>[] newBuckets = newBuckets(oldBuckets.length * 2);
        for (Bucket<K, V> oldBucket : oldBuckets) {
            for (Node<K, V> node = oldBucket.head; node != null; node = node.next) {
                Bucket<K, V> bucket = newBuckets[bucketIndex(node.hash, newBuckets.length)];
                bucket.head = new Node<K, V>(node.hash, node.key, node.value, bucket.head);
            }
        }
        stripe.buckets = newBuckets;
    }

    public V remove(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripe(hash);
        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            Bucket<K, V> bucket = buckets[bucketIndex(hash, buckets.length)];
            Node<K, V> oldHead = bucket.head;
            Node<K, V> oldNode = find(oldHead, key, hash);

            if (oldNode == null) {
                return null;
            }

            Node<K, V> newHead = null;
            for (Node<K, V> node = oldHead; node != null; node = node.next) {
                if (node != oldNode) {
                    newHead = new Node<K, V>(node.hash, node.key, node.value, newHead);
                }
            }
            bucket.head = newHead;
            stripe.size--;
            return oldNode.value;
        }
    }

    // The result is exact when updates are quiescent and may reflect different
    // instants for different stripes during concurrent updates.
    public int size() {
        long total = 0;
        for (Stripe<K, V> stripe : stripes) {
            synchronized (stripe) {
                total += stripe.size;
            }
            if (total >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    // Traversal is weakly consistent and invokes the consumer without locks.
    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }
        for (Stripe<K, V> stripe : stripes) {
            Bucket<K, V>[] buckets = stripe.buckets;
            for (Bucket<K, V> bucket : buckets) {
                for (Node<K, V> node = bucket.head; node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }
}
