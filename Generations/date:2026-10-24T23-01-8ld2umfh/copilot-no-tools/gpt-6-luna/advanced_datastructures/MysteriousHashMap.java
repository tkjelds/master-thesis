@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int DEFAULT_STRIPE_COUNT = 16;

    private final Stripe<K, V>[] stripes;

    public MysteriousHashMap() {
        this(DEFAULT_INITIAL_CAPACITY, DEFAULT_STRIPE_COUNT);
    }

    public MysteriousHashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_STRIPE_COUNT);
    }

    @SuppressWarnings("unchecked")
    public MysteriousHashMap(int initialCapacity, int stripeCount) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive");
        }

        this.stripes = (Stripe<K, V>[]) new Stripe[stripeCount];
        int capacityPerStripe = initialCapacity / stripeCount;
        if (initialCapacity % stripeCount != 0) {
            capacityPerStripe++;
        }
        if (capacityPerStripe < 1) {
            capacityPerStripe = 1;
        }

        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Stripe<K, V>(capacityPerStripe);
        }
    }

    public boolean containsKey(K k) {
        requireKey(k);
        int hash = spread(k.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];
        Node<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];

        while (node != null) {
            if (node.key.equals(k)) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    public V get(K k) {
        requireKey(k);
        int hash = spread(k.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];
        Node<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];

        while (node != null) {
            if (node.key.equals(k)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public V put(K k, V v) {
        requireKey(k);
        requireValue(v);

        int hash = spread(k.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];

        synchronized (stripe) {
            Node<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> node = buckets[index];
            int prefixLength = 0;

            while (node != null && !node.key.equals(k)) {
                prefixLength++;
                node = node.next;
            }

            Node<K, V>[] updatedBuckets = buckets.clone();
            if (node != null) {
                Node<K, V>[] prefix = (Node<K, V>[]) new Node[prefixLength];
                Node<K, V> current = buckets[index];
                for (int i = 0; i < prefixLength; i++) {
                    prefix[i] = current;
                    current = current.next;
                }

                Node<K, V> updated = new Node<K, V>(node.key, v, node.next);
                for (int i = prefixLength - 1; i >= 0; i--) {
                    updated = new Node<K, V>(prefix[i].key, prefix[i].value, updated);
                }
                updatedBuckets[index] = updated;
                stripe.buckets = updatedBuckets;
                return node.value;
            }

            updatedBuckets[index] = new Node<K, V>(k, v, buckets[index]);
            stripe.buckets = updatedBuckets;
            stripe.size++;

            if (stripe.size > threshold(buckets.length)
                    && buckets.length <= Integer.MAX_VALUE / 2) {
                resize(stripe, updatedBuckets);
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public V remove(K k) {
        requireKey(k);

        int hash = spread(k.hashCode());
        Stripe<K, V> stripe = stripes[stripeIndex(hash)];

        synchronized (stripe) {
            Node<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> node = buckets[index];
            int prefixLength = 0;

            while (node != null && !node.key.equals(k)) {
                prefixLength++;
                node = node.next;
            }
            if (node == null) {
                return null;
            }

            Node<K, V>[] prefix = (Node<K, V>[]) new Node[prefixLength];
            Node<K, V> current = buckets[index];
            for (int i = 0; i < prefixLength; i++) {
                prefix[i] = current;
                current = current.next;
            }

            Node<K, V> updated = node.next;
            for (int i = prefixLength - 1; i >= 0; i--) {
                updated = new Node<K, V>(prefix[i].key, prefix[i].value, updated);
            }

            Node<K, V>[] updatedBuckets = buckets.clone();
            updatedBuckets[index] = updated;
            stripe.buckets = updatedBuckets;
            stripe.size--;
            return node.value;
        }
    }

    public int size() {
        long total = 0;
        for (Stripe<K, V> stripe : stripes) {
            total += stripe.size;
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(java.util.function.BiConsumer<? super K, ? super V> consumer) {
        if (consumer == null) {
            throw new NullPointerException("consumer");
        }

        for (Stripe<K, V> stripe : stripes) {
            Node<K, V>[] buckets = stripe.buckets;
            for (int i = 0; i < buckets.length; i++) {
                Node<K, V> node = buckets[i];
                while (node != null) {
                    consumer.accept(node.key, node.value);
                    node = node.next;
                }
            }
        }
    }

    private int stripeIndex(int hash) {
        return (hash & 0x7fffffff) % stripes.length;
    }

    private static int bucketIndex(int hash, int bucketCount) {
        return (hash & 0x7fffffff) % bucketCount;
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int threshold(int capacity) {
        int result = (capacity / 4) * 3 + ((capacity % 4) * 3) / 4;
        return result < 1 ? 1 : result;
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> void resize(Stripe<K, V> stripe, Node<K, V>[] oldBuckets) {
        int newCapacity = oldBuckets.length * 2;
        Node<K, V>[] newBuckets = (Node<K, V>[]) new Node[newCapacity];

        for (int i = 0; i < oldBuckets.length; i++) {
            Node<K, V> node = oldBuckets[i];
            while (node != null) {
                int hash = spread(node.key.hashCode());
                int index = bucketIndex(hash, newCapacity);
                newBuckets[index] = new Node<K, V>(node.key, node.value, newBuckets[index]);
                node = node.next;
            }
        }

        stripe.buckets = newBuckets;
    }

    private static final class Stripe<K, V> {
        private volatile Node<K, V>[] buckets;
        private volatile int size;

        @SuppressWarnings("unchecked")
        private Stripe(int initialCapacity) {
            this.buckets = (Node<K, V>[]) new Node[initialCapacity];
        }
    }

    private static final class Node<K, V> {
        private final K key;
        private final V value;
        private final Node<K, V> next;

        private Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
