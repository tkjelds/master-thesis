// Self-contained: no imports. Since java.util.function.BiConsumer cannot be imported,
// a same-shaped functional interface is defined in this file.
interface BiConsumer<A, B> {
    void accept(A a, B b);
}

@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}


    private static final int STRIPE_COUNT = 16; // power of two
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;
    private static final int STRIPE_SHIFT = 4;  // log2(STRIPE_COUNT)
    private static final int INITIAL_BUCKETS = 8; // power of two

    // Immutable key-value pair that is also a cell of an immutable list.
    private static final class Node<K, V> {
        final K key;
        final V value;
        final Node<K, V> next;

        Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    // Each stripe owns its own bucket array. Its monitor guards all writes.
    private static final class Stripe<K, V> {
        // Written only while holding this stripe's monitor.
        // Readers read 'count' first (volatile) and then 'buckets' (volatile), so every
        // array element write made before the corresponding count write is visible.
        volatile Node<K, V>[] buckets;
        volatile int count;

        Stripe() {
            buckets = newArray(INITIAL_BUCKETS);
            count = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newArray(int n) {
        return (Node<K, V>[]) new Node[n];
    }

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new Stripe<K, V>();
        }
    }

    private static int hash(Object k) {
        int h = k.hashCode();
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        return h;
    }

    private static <K, V> Node<K, V> find(Node<K, V> n, Object key) {
        while (n != null) {
            if (n.key == key || n.key.equals(key)) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    private static <K, V> Node<K, V> replace(Node<K, V> head, K key, V value) {
        if (head == null) {
            return null;
        }
        if (head.key == key || head.key.equals(key)) {
            return new Node<K, V>(head.key, value, head.next);
        }
        return new Node<K, V>(head.key, head.value, replace(head.next, key, value));
    }

    private static <K, V> Node<K, V> removeKey(Node<K, V> head, Object key) {
        if (head == null) {
            return null;
        }
        if (head.key == key || head.key.equals(key)) {
            return head.next;
        }
        Node<K, V> rest = removeKey(head.next, key);
        if (rest == head.next) {
            return head;
        }
        return new Node<K, V>(head.key, head.value, rest);
    }

    public boolean containsKey(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        int c = s.count; // volatile read: establishes visibility
        Node<K, V>[] b = s.buckets;
        return find(b[(h >>> STRIPE_SHIFT) & (b.length - 1)], k) != null;
    }

    public V get(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        int c = s.count; // volatile read
        Node<K, V>[] b = s.buckets;
        Node<K, V> n = find(b[(h >>> STRIPE_SHIFT) & (b.length - 1)], k);
        return n == null ? null : n.value;
    }

    public V put(K k, V v) {
        if (k == null || v == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        synchronized (s) {
            Node<K, V>[] b = s.buckets;
            int idx = (h >>> STRIPE_SHIFT) & (b.length - 1);
            Node<K, V> head = b[idx];
            Node<K, V> existing = find(head, k);
            int newCount = s.count;
            V old = null;
            if (existing != null) {
                old = existing.value;
                b[idx] = replace(head, k, v);
            } else {
                b[idx] = new Node<K, V>(k, v, head);
                newCount++;
                if (newCount > b.length - (b.length >> 2)) { // load factor 0.75
                    resize(s, b);
                }
            }
            s.count = newCount; // volatile write publishes all preceding writes
            return old;
        }
    }

    // Caller holds the stripe's monitor.
    private void resize(Stripe<K, V> s, Node<K, V>[] old) {
        Node<K, V>[] nb = newArray(old.length * 2);
        int mask = nb.length - 1;
        for (int i = 0; i < old.length; i++) {
            for (Node<K, V> n = old[i]; n != null; n = n.next) {
                int idx = (hash(n.key) >>> STRIPE_SHIFT) & mask;
                nb[idx] = new Node<K, V>(n.key, n.value, nb[idx]);
            }
        }
        s.buckets = nb; // volatile publication of fully built array
    }

    public V remove(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        synchronized (s) {
            Node<K, V>[] b = s.buckets;
            int idx = (h >>> STRIPE_SHIFT) & (b.length - 1);
            Node<K, V> head = b[idx];
            Node<K, V> existing = find(head, k);
            if (existing == null) {
                return null;
            }
            b[idx] = removeKey(head, k);
            s.count = s.count - 1; // volatile write publishes the change
            return existing.value;
        }
    }

    public int size() {
        long total = 0;
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<K, V> s = stripes[i];
            synchronized (s) {
                total += s.count;
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) throw new NullPointerException();
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<K, V> s = stripes[i];
            int c = s.count; // volatile read
            Node<K, V>[] b = s.buckets;
            for (int j = 0; j < b.length; j++) {
                for (Node<K, V> n = b[j]; n != null; n = n.next) {
                    consumer.accept(n.key, n.value);
                }
            }
        }
    }
}
