interface BiConsumer<K, V> {
    void accept(K key, V value);
}

@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    private static final int STRIPE_COUNT = 16;
    private static final int INITIAL_BUCKET_COUNT = 16;
    private static final int STRIPE_SHIFT = 4;

    private final Stripe<K, V>[] stripes;

    public MysteriousHashMap() {
        @SuppressWarnings("unchecked")
        Stripe<K, V>[] created = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            created[i] = new Stripe<>(INITIAL_BUCKET_COUNT);
        }
        stripes = created;
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int hash = spreadHash(key.hashCode());
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
        requireKey(key);
        int hash = spreadHash(key.hashCode());
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
        requireKey(key);
        requireValue(value);

        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];

        synchronized (stripe) {
            Node<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> node = buckets[index];
            Node<K, V> reversedPrefix = null;

            while (node != null) {
                if (node.hash == hash && key.equals(node.key)) {
                    V previousValue = node.value;
                    Node<K, V> replacement =
                            new Node<>(hash, key, value, node.next);

                    while (reversedPrefix != null) {
                        replacement = new Node<>(
                                reversedPrefix.hash,
                                reversedPrefix.key,
                                reversedPrefix.value,
                                replacement);
                        reversedPrefix = reversedPrefix.next;
                    }

                    Node<K, V>[] updated = buckets.clone();
                    updated[index] = replacement;
                    stripe.buckets = updated;
                    return previousValue;
                }

                reversedPrefix = new Node<>(
                        node.hash, node.key, node.value, reversedPrefix);
                node = node.next;
            }

            Node<K, V>[] updated = buckets.clone();
            updated[index] = new Node<>(hash, key, value, buckets[index]);
            stripe.size++;

            if (stripe.size > (updated.length * 3) / 4
                    && updated.length < (1 << 30)) {
                updated = resize(updated);
            }
            stripe.buckets = updated;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        int hash = spreadHash(key.hashCode());
        Stripe<K, V> stripe = stripes[hash & (STRIPE_COUNT - 1)];

        synchronized (stripe) {
            Node<K, V>[] buckets = stripe.buckets;
            int index = bucketIndex(hash, buckets.length);
            Node<K, V> node = buckets[index];
            Node<K, V> reversedPrefix = null;

            while (node != null) {
                if (node.hash == hash && key.equals(node.key)) {
                    Node<K, V> replacement = node.next;
                    while (reversedPrefix != null) {
                        replacement = new Node<>(
                                reversedPrefix.hash,
                                reversedPrefix.key,
                                reversedPrefix.value,
                                replacement);
                        reversedPrefix = reversedPrefix.next;
                    }

                    Node<K, V>[] updated = buckets.clone();
                    updated[index] = replacement;
                    stripe.size--;
                    stripe.buckets = updated;
                    return node.value;
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

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int bucketIndex(int hash, int bucketCount) {
        return (hash >>> STRIPE_SHIFT) & (bucketCount - 1);
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

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] resize(Node<K, V>[] buckets) {
        Node<K, V>[] expanded = (Node<K, V>[]) new Node[buckets.length * 2];
        for (Node<K, V> head : buckets) {
            for (Node<K, V> node = head; node != null; node = node.next) {
                int index = bucketIndex(node.hash, expanded.length);
                expanded[index] = new Node<>(
                        node.hash, node.key, node.value, expanded[index]);
            }
        }
        return expanded;
    }

    private static final class Stripe<K, V> {
        volatile Node<K, V>[] buckets;
        int size;

        @SuppressWarnings("unchecked")
        Stripe(int bucketCount) {
            buckets = (Node<K, V>[]) new Node[bucketCount];
        }
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
}
