@MysteriousHashMap.ThreadSafe
public final class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int STRIPE_BITS = 4;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int INITIAL_BUCKET_COUNT = 16;
    private static final int MAX_BUCKET_COUNT = 1 << (32 - STRIPE_BITS);

    private final Stripe<K, V>[] stripes;

    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

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
        long size; // Guarded by this stripe's monitor.

        Stripe() {
            buckets = createBuckets(INITIAL_BUCKET_COUNT);
        }
    }

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe<?, ?>[STRIPE_COUNT];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe<K, V>();
        }
    }

    /*
     * Null keys and values are forbidden. Readers never acquire monitors.
     * Writers wait only for their stripe's monitor; size acquires all stripe
     * monitors in index order. There is no capacity-related blocking: at the
     * maximum bucket count, immutable collision lists can continue growing.
     */
    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public V get(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        Bucket<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = find(
                buckets[bucketIndex(hash, buckets.length)].head, hash, key);
        return node == null ? null : node.value;
    }

    public V put(K key, V value) {
        int hash = hash(key);
        if (value == null) {
            throw new NullPointerException();
        }

        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            Bucket<K, V> bucket = buckets[bucketIndex(hash, buckets.length)];
            Node<K, V> existing = find(bucket.head, hash, key);

            if (existing != null) {
                Node<K, V> replacement = new Node<K, V>(
                        existing.hash, existing.key, value, existing.next);
                bucket.head = replacePrefix(bucket.head, existing, replacement);
                return existing.value;
            }

            int threshold = buckets.length - (buckets.length >>> 2);
            if (stripe.size >= threshold && buckets.length < MAX_BUCKET_COUNT) {
                buckets = grow(buckets);
                stripe.buckets = buckets;
                bucket = buckets[bucketIndex(hash, buckets.length)];
            }

            bucket.head = new Node<K, V>(hash, key, value, bucket.head);
            stripe.size++;
            return null;
        }
    }

    public V remove(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];

        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            Bucket<K, V> bucket = buckets[bucketIndex(hash, buckets.length)];
            Node<K, V> existing = find(bucket.head, hash, key);

            if (existing == null) {
                return null;
            }

            bucket.head = replacePrefix(bucket.head, existing, existing.next);
            stripe.size--;
            return existing.value;
        }
    }

    /**
     * Returns an exact snapshot, capped at Integer.MAX_VALUE.
     */
    public int size() {
        return sizeWithLocks(0);
    }

    /**
     * Traversal is weakly consistent and invokes callbacks without locks.
     * Callback exceptions propagate to the caller.
     */
    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }

        for (int i = 0; i < stripes.length; i++) {
            Bucket<K, V>[] buckets = stripes[i].buckets;
            for (int j = 0; j < buckets.length; j++) {
                Node<K, V> node = buckets[j].head;
                while (node != null) {
                    consumer.accept(node.key, node.value);
                    node = node.next;
                }
            }
        }
    }

    private int sizeWithLocks(int index) {
        if (index == stripes.length) {
            long total = 0;
            for (int i = 0; i < stripes.length; i++) {
                total += stripes[i].size;
                if (total >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
            return (int) total;
        }

        synchronized (stripes[index]) {
            return sizeWithLocks(index + 1);
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
        // Stripe selection already consumes the lowest STRIPE_BITS bits.
        return (hash >>> STRIPE_BITS) & (bucketCount - 1);
    }

    private static <K, V> Node<K, V> find(
            Node<K, V> head, int hash, K key) {
        for (Node<K, V> node = head; node != null; node = node.next) {
            if (node.hash == hash
                    && (key == node.key || key.equals(node.key))) {
                return node;
            }
        }
        return null;
    }

    private static <K, V> Node<K, V> replacePrefix(
            Node<K, V> head, Node<K, V> target, Node<K, V> replacement) {
        // Copy iteratively so arbitrarily long collision lists need no recursion.
        Node<K, V> reversed = null;
        for (Node<K, V> node = head; node != target; node = node.next) {
            reversed = new Node<K, V>(
                    node.hash, node.key, node.value, reversed);
        }

        Node<K, V> result = replacement;
        for (Node<K, V> node = reversed; node != null; node = node.next) {
            result = new Node<K, V>(
                    node.hash, node.key, node.value, result);
        }
        return result;
    }

    private static <K, V> Bucket<K, V>[] grow(Bucket<K, V>[] oldBuckets) {
        Bucket<K, V>[] newBuckets = createBuckets(oldBuckets.length << 1);

        for (int i = 0; i < oldBuckets.length; i++) {
            for (Node<K, V> node = oldBuckets[i].head;
                    node != null; node = node.next) {
                Bucket<K, V> bucket =
                        newBuckets[bucketIndex(node.hash, newBuckets.length)];
                bucket.head = new Node<K, V>(
                        node.hash, node.key, node.value, bucket.head);
            }
        }

        // Old buckets remain unchanged for readers holding the old array.
        return newBuckets;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Bucket<K, V>[] createBuckets(int count) {
        Bucket<K, V>[] buckets =
                (Bucket<K, V>[]) new Bucket<?, ?>[count];
        for (int i = 0; i < count; i++) {
            buckets[i] = new Bucket<K, V>();
        }
        return buckets;
    }
}
