@MysteriousHashMap.ThreadSafe
public final class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int STRIPE_COUNT = 16;
    private static final int INITIAL_BUCKET_COUNT = 16;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;
    private static final int STRIPE_SHIFT = 4;

    private final Stripe<K, V>[] stripes;

    public MysteriousHashMap() {
        @SuppressWarnings("unchecked")
        Stripe<K, V>[] stripeArray = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripeArray[i] = new Stripe<K, V>(INITIAL_BUCKET_COUNT);
        }
        stripes = stripeArray;
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & STRIPE_MASK];
        Node<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];
        while (node != null) {
            if (node.key == key || key.equals(node.key)) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    public V get(K key) {
        requireKey(key);
        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & STRIPE_MASK];
        Node<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)];
        while (node != null) {
            if (node.key == key || key.equals(node.key)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);

        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & STRIPE_MASK];

        synchronized (stripe) {
            Node<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(hash, oldBuckets.length);
            Node<K, V> node = oldBuckets[index];
            Node<K, V> prefixReversed = null;

            while (node != null) {
                if (node.key == key || key.equals(node.key)) {
                    V previousValue = node.value;
                    Node<K, V> replacement =
                            new Node<K, V>(node.key, value, node.next);
                    replacement = copyReversedPrefix(prefixReversed, replacement);

                    Node<K, V>[] newBuckets = oldBuckets.clone();
                    newBuckets[index] = replacement;
                    stripe.buckets = newBuckets;
                    return previousValue;
                }
                prefixReversed = new Node<K, V>(node.key, node.value, prefixReversed);
                node = node.next;
            }

            Node<K, V>[] newBuckets = oldBuckets.clone();
            newBuckets[index] = new Node<K, V>(key, value, oldBuckets[index]);
            int newSize = stripe.size + 1;

            if (oldBuckets.length < (1 << 30)
                    && newSize > oldBuckets.length - (oldBuckets.length >>> 2)) {
                newBuckets = resize(newBuckets, oldBuckets.length << 1);
            }

            stripe.buckets = newBuckets;
            stripe.size = newSize;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);

        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & STRIPE_MASK];

        synchronized (stripe) {
            Node<K, V>[] oldBuckets = stripe.buckets;
            int index = bucketIndex(hash, oldBuckets.length);
            Node<K, V> node = oldBuckets[index];
            Node<K, V> prefixReversed = null;

            while (node != null) {
                if (node.key == key || key.equals(node.key)) {
                    Node<K, V> replacement =
                            copyReversedPrefix(prefixReversed, node.next);
                    Node<K, V>[] newBuckets = oldBuckets.clone();
                    newBuckets[index] = replacement;
                    stripe.buckets = newBuckets;
                    stripe.size = stripe.size - 1;
                    return node.value;
                }
                prefixReversed = new Node<K, V>(node.key, node.value, prefixReversed);
                node = node.next;
            }
            return null;
        }
    }

    public int size() {
        long total = 0;
        for (int i = 0; i < stripes.length; i++) {
            total += stripes[i].size;
        }
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(java.util.function.BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }

        for (int i = 0; i < stripes.length; i++) {
            Node<K, V>[] buckets = stripes[i].buckets;
            for (int j = 0; j < buckets.length; j++) {
                Node<K, V> node = buckets[j];
                while (node != null) {
                    consumer.accept(node.key, node.value);
                    node = node.next;
                }
            }
        }
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int bucketIndex(int hash, int bucketCount) {
        return (hash >>> STRIPE_SHIFT) & (bucketCount - 1);
    }

    private static <K, V> Node<K, V> copyReversedPrefix(
            Node<K, V> prefixReversed, Node<K, V> tail) {
        Node<K, V> result = tail;
        Node<K, V> node = prefixReversed;
        while (node != null) {
            result = new Node<K, V>(node.key, node.value, result);
            node = node.next;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] resize(
            Node<K, V>[] oldBuckets, int newCapacity) {
        Node<K, V>[] newBuckets = (Node<K, V>[]) new Node[newCapacity];
        for (int i = 0; i < oldBuckets.length; i++) {
            Node<K, V> node = oldBuckets[i];
            while (node != null) {
                int index = bucketIndex(spreadHash(node.key.hashCode()), newCapacity);
                newBuckets[index] =
                        new Node<K, V>(node.key, node.value, newBuckets[index]);
                node = node.next;
            }
        }
        return newBuckets;
    }

    private static final class Stripe<K, V> {
        private volatile Node<K, V>[] buckets;
        private volatile int size;

        @SuppressWarnings("unchecked")
        private Stripe(int bucketCount) {
            buckets = (Node<K, V>[]) new Node[bucketCount];
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
