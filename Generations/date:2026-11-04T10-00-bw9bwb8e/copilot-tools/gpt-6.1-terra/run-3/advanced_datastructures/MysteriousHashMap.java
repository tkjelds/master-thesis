@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_COUNT = 16;
    private static final int STRIPE_BITS = 4;
    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

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

    private static final class Table<K, V> {
        final Node<K, V>[] buckets;
        final long size;

        Table(Node<K, V>[] buckets, long size) {
            this.buckets = buckets;
            this.size = size;
        }
    }

    private static final class Stripe<K, V> {
        volatile Table<K, V> table;

        Stripe() {
            table = new Table<K, V>(newBuckets(INITIAL_CAPACITY), 0);
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
    private static <K, V> Node<K, V>[] newBuckets(int capacity) {
        return (Node<K, V>[]) new Node<?, ?>[capacity];
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int bucketIndex(int hash, int capacity) {
        return (hash >>> STRIPE_BITS) & (capacity - 1);
    }

    private Stripe<K, V> stripe(int hash) {
        return stripes[hash & (STRIPE_COUNT - 1)];
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = hash(key);
        Table<K, V> table = stripe(hash).table;
        Node<K, V> node = table.buckets[bucketIndex(hash, table.buckets.length)];
        while (node != null) {
            if (node.hash == hash && key.equals(node.key)) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        int hash = hash(key);
        Stripe<K, V> stripe = stripe(hash);
        synchronized (stripe) {
            Table<K, V> old = stripe.table;
            int index = bucketIndex(hash, old.buckets.length);
            Node<K, V> match = old.buckets[index];
            while (match != null && (match.hash != hash || !key.equals(match.key))) {
                match = match.next;
            }

            if (match != null) {
                Node<K, V>[] buckets = copyBuckets(old.buckets);
                Node<K, V> replacement = null;
                for (Node<K, V> node = old.buckets[index]; node != null; node = node.next) {
                    replacement = new Node<K, V>(node.hash, node.key,
                            node == match ? value : node.value, replacement);
                }
                buckets[index] = replacement;
                stripe.table = new Table<K, V>(buckets, old.size);
                return match.value;
            }

            int capacity = old.buckets.length;
            Node<K, V>[] buckets;
            if (capacity < MAX_CAPACITY && old.size + 1 > capacity - capacity / 4) {
                buckets = newBuckets(capacity * 2);
                for (Node<K, V> head : old.buckets) {
                    for (Node<K, V> node = head; node != null; node = node.next) {
                        int target = bucketIndex(node.hash, buckets.length);
                        buckets[target] = new Node<K, V>(
                                node.hash, node.key, node.value, buckets[target]);
                    }
                }
            } else {
                buckets = copyBuckets(old.buckets);
            }
            index = bucketIndex(hash, buckets.length);
            buckets[index] = new Node<K, V>(hash, key, value, buckets[index]);
            stripe.table = new Table<K, V>(buckets, old.size + 1);
            return null;
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = hash(key);
        Stripe<K, V> stripe = stripe(hash);
        synchronized (stripe) {
            Table<K, V> old = stripe.table;
            int index = bucketIndex(hash, old.buckets.length);
            Node<K, V> match = old.buckets[index];
            while (match != null && (match.hash != hash || !key.equals(match.key))) {
                match = match.next;
            }
            if (match == null) {
                return null;
            }
            Node<K, V>[] buckets = copyBuckets(old.buckets);
            Node<K, V> replacement = null;
            for (Node<K, V> node = old.buckets[index]; node != null; node = node.next) {
                if (node != match) {
                    replacement = new Node<K, V>(
                            node.hash, node.key, node.value, replacement);
                }
            }
            buckets[index] = replacement;
            stripe.table = new Table<K, V>(buckets, old.size - 1);
            return match.value;
        }
    }

    private static <K, V> Node<K, V>[] copyBuckets(Node<K, V>[] original) {
        Node<K, V>[] copy = newBuckets(original.length);
        for (int i = 0; i < original.length; i++) {
            copy[i] = original[i];
        }
        return copy;
    }

    // Writers and size wait for stripe monitors; readers never acquire them.
    public int size() {
        long total = 0;
        for (Stripe<K, V> stripe : stripes) {
            synchronized (stripe) {
                total += stripe.table.size;
            }
        }
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }
        for (Stripe<K, V> stripe : stripes) {
            Table<K, V> table = stripe.table;
            for (Node<K, V> head : table.buckets) {
                for (Node<K, V> node = head; node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }
}
