@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_BITS = 4;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int INITIAL_CAPACITY = 8;
    private static final int MAX_CAPACITY = 1 << (32 - STRIPE_BITS);

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

    private static final class State<K, V> {
        final Node<K, V>[] buckets;
        final long size;

        State(Node<K, V>[] buckets, long size) {
            this.buckets = buckets;
            this.size = size;
        }
    }

    private static final class Stripe<K, V> {
        volatile State<K, V> state;

        Stripe() {
            state = new State<K, V>(newBuckets(INITIAL_CAPACITY), 0);
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
        if (key == null) {
            throw new NullPointerException();
        }
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private Stripe<K, V> stripeFor(int hash) {
        return stripes[hash & (STRIPE_COUNT - 1)];
    }

    private static int bucketIndex(int hash, int capacity) {
        return (hash >>> STRIPE_BITS) & (capacity - 1);
    }

    private static <K, V> Node<K, V> find(
            Node<K, V> head, int hash, K key) {
        for (Node<K, V> node = head; node != null; node = node.next) {
            if (node.hash == hash && key.equals(node.key)) {
                return node;
            }
        }
        return null;
    }

    private static <K, V> Node<K, V>[] copyBuckets(Node<K, V>[] buckets) {
        Node<K, V>[] copy = newBuckets(buckets.length);
        for (int i = 0; i < buckets.length; i++) {
            copy[i] = buckets[i];
        }
        return copy;
    }

    private static <K, V> Node<K, V> replace(
            Node<K, V> head, Node<K, V> target, Node<K, V> replacement) {
        Node<K, V> reversed = null;
        for (Node<K, V> node = head; node != target; node = node.next) {
            reversed = new Node<K, V>(
                    node.hash, node.key, node.value, reversed);
        }
        while (reversed != null) {
            replacement = new Node<K, V>(
                    reversed.hash, reversed.key, reversed.value, replacement);
            reversed = reversed.next;
        }
        return replacement;
    }

    private static <K, V> Node<K, V>[] grow(Node<K, V>[] buckets) {
        Node<K, V>[] larger = newBuckets(buckets.length * 2);
        for (int i = 0; i < buckets.length; i++) {
            for (Node<K, V> node = buckets[i]; node != null; node = node.next) {
                int index = bucketIndex(node.hash, larger.length);
                larger[index] = new Node<K, V>(
                        node.hash, node.key, node.value, larger[index]);
            }
        }
        return larger;
    }

    public boolean containsKey(K key) {
        int hash = hash(key);
        State<K, V> state = stripeFor(hash).state;
        int index = bucketIndex(hash, state.buckets.length);
        return find(state.buckets[index], hash, key) != null;
    }

    public V get(K key) {
        int hash = hash(key);
        State<K, V> state = stripeFor(hash).state;
        int index = bucketIndex(hash, state.buckets.length);
        Node<K, V> node = find(state.buckets[index], hash, key);
        return node == null ? null : node.value;
    }

    public V put(K key, V value) {
        int hash = hash(key);
        if (value == null) {
            throw new NullPointerException();
        }

        Stripe<K, V> stripe = stripeFor(hash);
        synchronized (stripe) {
            State<K, V> state = stripe.state;
            int index = bucketIndex(hash, state.buckets.length);
            Node<K, V> head = state.buckets[index];
            Node<K, V> existing = find(head, hash, key);
            Node<K, V>[] buckets = copyBuckets(state.buckets);

            if (existing != null) {
                Node<K, V> replacement = new Node<K, V>(
                        existing.hash, existing.key, value, existing.next);
                buckets[index] = replace(head, existing, replacement);
                stripe.state = new State<K, V>(buckets, state.size);
                return existing.value;
            }

            buckets[index] = new Node<K, V>(hash, key, value, head);
            long newSize = state.size + 1;
            if (buckets.length < MAX_CAPACITY
                    && newSize > (long) buckets.length * 3 / 4) {
                buckets = grow(buckets);
            }
            stripe.state = new State<K, V>(buckets, newSize);
            return null;
        }
    }

    public V remove(K key) {
        int hash = hash(key);
        Stripe<K, V> stripe = stripeFor(hash);
        synchronized (stripe) {
            State<K, V> state = stripe.state;
            int index = bucketIndex(hash, state.buckets.length);
            Node<K, V> head = state.buckets[index];
            Node<K, V> existing = find(head, hash, key);
            if (existing == null) {
                return null;
            }

            Node<K, V>[] buckets = copyBuckets(state.buckets);
            buckets[index] = replace(head, existing, existing.next);
            stripe.state = new State<K, V>(buckets, state.size - 1);
            return existing.value;
        }
    }

    public int size() {
        long total = 0;
        for (int i = 0; i < stripes.length; i++) {
            Stripe<K, V> stripe = stripes[i];
            synchronized (stripe) {
                long stripeSize = stripe.state.size;
                if (stripeSize >= Integer.MAX_VALUE - total) {
                    return Integer.MAX_VALUE;
                }
                total += stripeSize;
            }
        }
        return (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }
        for (int i = 0; i < stripes.length; i++) {
            State<K, V> state = stripes[i].state;
            for (int j = 0; j < state.buckets.length; j++) {
                for (Node<K, V> node = state.buckets[j];
                        node != null; node = node.next) {
                    consumer.accept(node.key, node.value);
                }
            }
        }
    }
}
