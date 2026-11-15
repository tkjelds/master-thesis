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
        // Only the list reference changes; every published node is immutable.
        volatile Node<K, V> head;
    }

    private static final class Stripe<K, V> {
        volatile Bucket<K, V>[] buckets;
        long size;

        Stripe() {
            buckets = newBuckets(INITIAL_BUCKET_COUNT);
        }
    }

    public MysteriousHashMap() {
        Stripe<K, V>[] initial =
                (Stripe<K, V>[]) new Stripe<?, ?>[STRIPE_COUNT];
        for (int i = 0; i < initial.length; i++) {
            initial[i] = new Stripe<K, V>();
        }
        stripes = initial;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public V get(K key) {
        requireNonNull(key);
        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];
        Bucket<K, V>[] buckets = stripe.buckets;

        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)].head;
        while (node != null) {
            if (matches(node, hash, key)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);

        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];

        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            Bucket<K, V> bucket = buckets[bucketIndex(hash, buckets.length)];
            Node<K, V> head = bucket.head;

            for (Node<K, V> node = head; node != null; node = node.next) {
                if (matches(node, hash, key)) {
                    Node<K, V> replacement =
                            new Node<K, V>(hash, node.key, value, node.next);
                    bucket.head = replacePrefix(head, node, replacement);
                    return node.value;
                }
            }

            int capacity = buckets.length;
            int threshold = capacity - (capacity >>> 2);
            if (stripe.size >= threshold && capacity < MAX_BUCKET_COUNT) {
                grow(stripe);
                buckets = stripe.buckets;
                bucket = buckets[bucketIndex(hash, buckets.length)];
            }

            bucket.head = new Node<K, V>(hash, key, value, bucket.head);
            stripe.size++;
            return null;
        }
    }

    public V remove(K key) {
        requireNonNull(key);
        int hash = spread(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];

        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            Bucket<K, V> bucket = buckets[bucketIndex(hash, buckets.length)];
            Node<K, V> head = bucket.head;

            for (Node<K, V> node = head; node != null; node = node.next) {
                if (matches(node, hash, key)) {
                    bucket.head = replacePrefix(head, node, node.next);
                    stripe.size--;
                    return node.value;
                }
            }
            return null;
        }
    }

    public int size() {
        long count = sizeWithLocks(0);
        return count > 0x7fffffffL ? 0x7fffffff : (int) count;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        requireNonNull(consumer);

        // Weakly consistent traversal: callbacks never execute under a lock.
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

    private long sizeWithLocks(int index) {
        if (index == stripes.length) {
            return 0L;
        }

        // A fixed acquisition order gives an exact snapshot without lock cycles.
        Stripe<K, V> stripe = stripes[index];
        synchronized (stripe) {
            return stripe.size + sizeWithLocks(index + 1);
        }
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int bucketIndex(int hash, int capacity) {
        return (hash >>> STRIPE_BITS) & (capacity - 1);
    }

    private static <K, V> boolean matches(Node<K, V> node, int hash, K key) {
        return node.hash == hash
                && (node.key == key || key.equals(node.key));
    }

    private static <K, V> Bucket<K, V>[] newBuckets(int capacity) {
        Bucket<K, V>[] buckets =
                (Bucket<K, V>[]) new Bucket<?, ?>[capacity];
        for (int i = 0; i < capacity; i++) {
            buckets[i] = new Bucket<K, V>();
        }
        return buckets;
    }

    private static <K, V> Node<K, V> replacePrefix(
            Node<K, V> head,
            Node<K, V> target,
            Node<K, V> replacement) {

        // Rebuild iteratively so long collision chains cannot exhaust the stack.
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

    // Called only while holding this stripe's monitor.
    private static <K, V> void grow(Stripe<K, V> stripe) {
        Bucket<K, V>[] oldBuckets = stripe.buckets;
        Bucket<K, V>[] newBuckets = newBuckets(oldBuckets.length << 1);

        for (int i = 0; i < oldBuckets.length; i++) {
            for (Node<K, V> node = oldBuckets[i].head;
                    node != null;
                    node = node.next) {
                Bucket<K, V> bucket =
                        newBuckets[bucketIndex(node.hash, newBuckets.length)];
                bucket.head = new Node<K, V>(
                        node.hash, node.key, node.value, bucket.head);
            }
        }

        // Old arrays and lists remain valid for readers already using them.
        stripe.buckets = newBuckets;
    }

    private static <T> void requireNonNull(T value) {
        if (value == null) {
            // Java raises NullPointerException without a library dependency.
            throw null;
        }
    }
}
