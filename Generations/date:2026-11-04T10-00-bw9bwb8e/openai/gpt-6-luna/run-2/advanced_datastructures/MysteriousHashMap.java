interface BiConsumer<K, V> {
    void accept(K key, V value);
}

@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_STRIPE_COUNT = 16;
    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int MAX_BUCKET_COUNT = 1 << 30;

    private final Stripe<K, V>[] stripes;

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

    private static final class Stripe<K, V> {
        // Published arrays are never modified after publication.
        volatile Node<K, V>[] buckets;
        volatile int count;

        Stripe(Node<K, V>[] buckets) {
            this.buckets = buckets;
        }
    }

    public MysteriousHashMap() {
        this(DEFAULT_STRIPE_COUNT, DEFAULT_INITIAL_CAPACITY);
    }

    /**
     * Creates a map with the given number of stripes and initial total capacity.
     * Each stripe receives at least one bucket.
     */
    @SuppressWarnings("unchecked")
    public MysteriousHashMap(int stripeCount, int initialCapacity) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive");
        }
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }

        stripes = (Stripe<K, V>[]) new Stripe[stripeCount];
        long perStripe = ((long) initialCapacity + stripeCount - 1) / stripeCount;
        int bucketCount = (int) Math.max(1L, perStripe);

        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Stripe<K, V>(newBucketArray(bucketCount));
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key);
        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];
        Node<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];

        while (node != null) {
            if (node.hash == hash && keysEqual(key, node.key)) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    public V get(K key) {
        requireNonNull(key);
        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];
        Node<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];

        while (node != null) {
            if (node.hash == hash && keysEqual(key, node.key)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);

        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];

        synchronized (stripe) {
            Node<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(hash, oldBuckets.length);
            Node<K, V> prefixReversed = null;
            Node<K, V> current = oldBuckets[index];

            while (current != null && !(current.hash == hash && keysEqual(key, current.key))) {
                prefixReversed = new Node<K, V>(
                    current.hash, current.key, current.value, prefixReversed);
                current = current.next;
            }

            if (current != null) {
                V previousValue = current.value;
                Node<K, V> replacement =
                    new Node<K, V>(current.hash, current.key, value, current.next);

                while (prefixReversed != null) {
                    replacement = new Node<K, V>(
                        prefixReversed.hash,
                        prefixReversed.key,
                        prefixReversed.value,
                        replacement);
                    prefixReversed = prefixReversed.next;
                }

                Node<K, V>[] updated = oldBuckets.clone();
                updated[index] = replacement;
                stripe.buckets = updated;
                return previousValue;
            }

            if (shouldGrow(stripe.count, oldBuckets.length)
                    && oldBuckets.length < MAX_BUCKET_COUNT) {
                int newCapacity = oldBuckets.length * 2;
                Node<K, V>[] grown = newBucketArray(newCapacity);

                for (Node<K, V> head : oldBuckets) {
                    for (Node<K, V> node = head; node != null; node = node.next) {
                        int newIndex = bucketIndex(node.hash, newCapacity);
                        grown[newIndex] = new Node<K, V>(
                            node.hash, node.key, node.value, grown[newIndex]);
                    }
                }

                int newIndex = bucketIndex(hash, newCapacity);
                grown[newIndex] = new Node<K, V>(hash, key, value, grown[newIndex]);
                stripe.buckets = grown;
            } else {
                Node<K, V>[] updated = oldBuckets.clone();
                updated[index] = new Node<K, V>(hash, key, value, oldBuckets[index]);
                stripe.buckets = updated;
            }

            stripe.count++;
            return null;
        }
    }

    public V remove(K key) {
        requireNonNull(key);

        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];

        synchronized (stripe) {
            Node<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(hash, oldBuckets.length);
            Node<K, V> prefixReversed = null;
            Node<K, V> current = oldBuckets[index];

            while (current != null && !(current.hash == hash && keysEqual(key, current.key))) {
                prefixReversed = new Node<K, V>(
                    current.hash, current.key, current.value, prefixReversed);
                current = current.next;
            }

            if (current == null) {
                return null;
            }

            V previousValue = current.value;
            Node<K, V> replacement = current.next;

            while (prefixReversed != null) {
                replacement = new Node<K, V>(
                    prefixReversed.hash,
                    prefixReversed.key,
                    prefixReversed.value,
                    replacement);
                prefixReversed = prefixReversed.next;
            }

            Node<K, V>[] updated = oldBuckets.clone();
            updated[index] = replacement;
            stripe.buckets = updated;
            stripe.count--;
            return previousValue;
        }
    }

    public int size() {
        long total = 0;

        for (Stripe<K, V> stripe : stripes) {
            synchronized (stripe) {
                total += stripe.count;
            }
        }

        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        requireNonNull(consumer);

        for (Stripe<K, V> stripe : stripes) {
            Node<K, V>[] buckets = stripe.buckets;
            for (Node<K, V> head : buckets) {
                for (Node<K, V> node = head; node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }

    private int stripeIndex(int hash) {
        return (hash & 0x7fffffff) % stripes.length;
    }

    private static int bucketIndex(int hash, int bucketCount) {
        return ((hash >>> 1) & 0x7fffffff) % bucketCount;
    }

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static boolean keysEqual(Object queryKey, Object storedKey) {
        return queryKey == storedKey || queryKey.equals(storedKey);
    }

    private static boolean shouldGrow(int count, int bucketCount) {
        return (((long) count + 1) * 4) > ((long) bucketCount * 3);
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newBucketArray(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }
}
