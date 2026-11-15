@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int DEFAULT_STRIPE_COUNT = 16;
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

        Stripe(int bucketCount) {
            buckets = newBucketArray(bucketCount);
        }
    }

    private final Stripe<K, V>[] stripes;
    private final int stripeShift;

    public MysteriousHashMap() {
        this(DEFAULT_STRIPE_COUNT);
    }

    @SuppressWarnings("unchecked")
    public MysteriousHashMap(int stripeCount) {
        if (stripeCount <= 0 || stripeCount > MAX_BUCKET_COUNT
                || (stripeCount & (stripeCount - 1)) != 0) {
            throw new IllegalArgumentException("stripeCount must be a positive power of two");
        }

        int shift = 0;
        for (int count = stripeCount; count > 1; count >>>= 1) {
            shift++;
        }
        stripeShift = shift;

        stripes = (Stripe<K, V>[]) new Stripe<?, ?>[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Stripe<>(INITIAL_BUCKET_COUNT);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Bucket<K, V>[] newBucketArray(int length) {
        Bucket<K, V>[] buckets = (Bucket<K, V>[]) new Bucket<?, ?>[length];
        for (int i = 0; i < length; i++) {
            buckets[i] = new Bucket<>();
        }
        return buckets;
    }

    private static int spreadHash(Object key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private Stripe<K, V> stripeFor(int hash) {
        return stripes[hash & (stripes.length - 1)];
    }

    private int bucketIndex(int hash, int bucketCount) {
        return (hash >>> stripeShift) & (bucketCount - 1);
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

    public boolean containsKey(K k) {
        requireKey(k);
        int hash = spreadHash(k);
        Stripe<K, V> stripe = stripeFor(hash);
        Bucket<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)].head;

        while (node != null) {
            if (node.hash == hash && node.key.equals(k)) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    public V get(K k) {
        requireKey(k);
        int hash = spreadHash(k);
        Stripe<K, V> stripe = stripeFor(hash);
        Bucket<K, V>[] buckets = stripe.buckets;
        Node<K, V> node = buckets[bucketIndex(hash, buckets.length)].head;

        while (node != null) {
            if (node.hash == hash && node.key.equals(k)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V put(K k, V v) {
        requireKey(k);
        requireValue(v);
        int hash = spreadHash(k);
        Stripe<K, V> stripe = stripeFor(hash);

        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> head = buckets[index].head;
            Node<K, V> reversedPrefix = null;
            Node<K, V> node = head;

            while (node != null) {
                if (node.hash == hash && node.key.equals(k)) {
                    V previousValue = node.value;
                    Node<K, V> replacement =
                            new Node<>(node.hash, node.key, v, node.next);
                    for (Node<K, V> prefix = reversedPrefix;
                         prefix != null;
                         prefix = prefix.next) {
                        replacement = new Node<>(
                                prefix.hash, prefix.key, prefix.value, replacement);
                    }
                    buckets[index].head = replacement;
                    return previousValue;
                }
                reversedPrefix = new Node<>(
                        node.hash, node.key, node.value, reversedPrefix);
                node = node.next;
            }

            Node<K, V> newHead = new Node<>(hash, k, v, head);
            long newSize = stripe.size + 1;
            if (newSize > (long) buckets.length * 3 / 4
                    && buckets.length < MAX_BUCKET_COUNT) {
                Bucket<K, V>[] grown = newBucketArray(buckets.length * 2);
                for (Bucket<K, V> bucket : buckets) {
                    for (Node<K, V> entry = bucket.head; entry != null; entry = entry.next) {
                        int newIndex = bucketIndex(entry.hash, grown.length);
                        grown[newIndex].head = new Node<>(
                                entry.hash, entry.key, entry.value, grown[newIndex].head);
                    }
                }
                int newIndex = bucketIndex(hash, grown.length);
                grown[newIndex].head = new Node<>(hash, k, v, grown[newIndex].head);
                stripe.buckets = grown;
            } else {
                buckets[index].head = newHead;
            }
            stripe.size = newSize;
            return null;
        }
    }

    public V remove(K k) {
        requireKey(k);
        int hash = spreadHash(k);
        Stripe<K, V> stripe = stripeFor(hash);

        synchronized (stripe) {
            Bucket<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> reversedPrefix = null;
            Node<K, V> node = buckets[index].head;

            while (node != null) {
                if (node.hash == hash && node.key.equals(k)) {
                    V previousValue = node.value;
                    Node<K, V> replacement = node.next;
                    for (Node<K, V> prefix = reversedPrefix;
                         prefix != null;
                         prefix = prefix.next) {
                        replacement = new Node<>(
                                prefix.hash, prefix.key, prefix.value, replacement);
                    }
                    buckets[index].head = replacement;
                    stripe.size--;
                    return previousValue;
                }
                reversedPrefix = new Node<>(
                        node.hash, node.key, node.value, reversedPrefix);
                node = node.next;
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
