/*
 * MysteriousHashMap: a concurrent hash map with lock striping.
 *
 * Layout:
 *  - A fixed top-level array of Stripe objects (STRIPE_COUNT entries).
 *  - Each Stripe owns its own bucket array. Each bucket is an immutable
 *    singly linked list of immutable Node objects (final fields only).
 *  - Writers (put, remove, size, resize) take the monitor of the owning Stripe
 *    with synchronized blocks. Only one thread mutates a given stripe at a time.
 *  - Readers (containsKey, get, forEach) take no lock. Each stripe publishes a
 *    volatile version counter that is written last by every writer. A reader
 *    reads that counter before reading the volatile table reference and the
 *    bucket elements, which establishes happens-before with the writer's
 *    updates. Node fields are final, so a reader never sees a partially
 *    constructed node. Readers observe a weakly consistent snapshot, as in
 *    ConcurrentHashMap.
 *
 * Blocking policy:
 *  - The map is unbounded. A stripe's bucket array is doubled in place, under
 *    that stripe's lock, when its load factor exceeds 0.75, so no operation
 *    ever has to wait for capacity. No wait/notify is needed.
 *  - null keys and null values are rejected with NullPointerException, as in
 *    ConcurrentHashMap.
 *
 * The BiConsumer interface is declared here as a nested type because the
 * source file must not import any package.
 */
@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}


    /** Functional interface used by forEach; declared locally to avoid imports. */
    public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }

    private static final int STRIPE_BITS = 4;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int INITIAL_BUCKETS = 8;

    /** Immutable key-value pair that also serves as an immutable list link. */
    static final class Node<K, V> {
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

    /**
     * A stripe owns one bucket array. Fields guarded by the stripe monitor are
     * accessed only while holding it. The table and version fields are volatile
     * so that lock-free readers can see them.
     */
    static final class Stripe<K, V> {
        private volatile Node<K, V>[] table;   // replaced on resize
        private volatile int version;          // written last by every mutation
        private int count;                     // guarded by this monitor

        Stripe() {
            table = newNodes(INITIAL_BUCKETS);
        }

        /** Lock-free snapshot of the current table. Reads version first. */
        Node<K, V>[] snapshot() {
            int ignored = version; // volatile read; establishes happens-before
            return table;
        }

        // ---- The methods below must be called while holding this monitor. ----

        Node<K, V> find(int h, Object key) {
            Node<K, V>[] t = table;
            for (Node<K, V> n = t[bucketIndex(h, t.length)]; n != null; n = n.next) {
                if (n.hash == h && eq(n.key, key)) {
                    return n;
                }
            }
            return null;
        }

        V put(int h, K key, V value) {
            Node<K, V>[] t = table;
            int idx = bucketIndex(h, t.length);
            Node<K, V> head = t[idx];
            Node<K, V> match = findIn(head, h, key);
            V old = null;
            Node<K, V> newHead;
            if (match != null) {
                old = match.value;
                newHead = replacePrefix(head, match, new Node<K, V>(h, key, value, match.next));
                t[idx] = newHead;
            } else {
                newHead = new Node<K, V>(h, key, value, head);
                t[idx] = newHead;
                count++;
                if (count > t.length - (t.length >>> 2)) {
                    resize(t.length << 1);
                }
            }
            version = version + 1; // volatile write last
            return old;
        }

        V remove(int h, Object key) {
            Node<K, V>[] t = table;
            int idx = bucketIndex(h, t.length);
            Node<K, V> head = t[idx];
            Node<K, V> match = findIn(head, h, key);
            if (match == null) {
                return null;
            }
            t[idx] = replacePrefix(head, match, match.next);
            count--;
            version = version + 1; // volatile write last
            return match.value;
        }

        int size() {
            return count;
        }

        private void resize(int newLength) {
            Node<K, V>[] old = table;
            Node<K, V>[] nt = newNodes(newLength);
            for (int i = 0; i < old.length; i++) {
                for (Node<K, V> n = old[i]; n != null; n = n.next) {
                    int j = bucketIndex(n.hash, newLength);
                    nt[j] = new Node<K, V>(n.hash, n.key, n.value, nt[j]);
                }
            }
            table = nt; // volatile write publishes the fully built array
        }

        private static <K, V> Node<K, V> findIn(Node<K, V> head, int h, Object key) {
            for (Node<K, V> n = head; n != null; n = n.next) {
                if (n.hash == h && eq(n.key, key)) {
                    return n;
                }
            }
            return null;
        }

        /**
         * Copies the list prefix before target, then links replacement in its place.
         * The original nodes are never modified.
         */
        private static <K, V> Node<K, V> replacePrefix(Node<K, V> head,
                                                      Node<K, V> target,
                                                      Node<K, V> replacement) {
            if (head == target) {
                return replacement;
            }
            return new Node<K, V>(head.hash, head.key, head.value,
                    replacePrefix(head.next, target, replacement));
        }
    }

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        Stripe<K, V>[] s = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < s.length; i++) {
            s[i] = new Stripe<K, V>();
        }
        this.stripes = s;
    }

    // ---------------- Public operations ----------------

    /** Lock-free. */
    public boolean containsKey(K k) {
        checkKey(k);
        int h = spread(k);
        return findNode(h, k) != null;
    }

    /** Lock-free. */
    public V get(K k) {
        checkKey(k);
        int h = spread(k);
        Node<K, V> n = findNode(h, k);
        return n == null ? null : n.value;
    }

    /** Locks only the owning stripe. Returns the previous value or null. */
    public V put(K k, V v) {
        checkKey(k);
        checkValue(v);
        int h = spread(k);
        Stripe<K, V> s = stripeFor(h);
        synchronized (s) {
            return s.put(h, k, v);
        }
    }

    /** Locks only the owning stripe. Returns the removed value or null. */
    public V remove(K k) {
        checkKey(k);
        int h = spread(k);
        Stripe<K, V> s = stripeFor(h);
        synchronized (s) {
            return s.remove(h, k);
        }
    }

    /** Locks each stripe in turn and sums the counts. */
    public int size() {
        long total = 0;
        for (int i = 0; i < stripes.length; i++) {
            Stripe<K, V> s = stripes[i];
            synchronized (s) {
                total += s.size();
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /** Lock-free traversal of each stripe's immutable snapshot. */
    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException("consumer");
        }
        for (int i = 0; i < stripes.length; i++) {
            Stripe<K, V> s = stripes[i];
            Node<K, V>[] t = s.snapshot();
            for (int b = 0; b < t.length; b++) {
                for (Node<K, V> n = t[b]; n != null; n = n.next) {
                    consumer.accept(n.key, n.value);
                }
            }
        }
    }

    // ---------------- Helpers ----------------

    private Node<K, V> findNode(int h, Object key) {
        Stripe<K, V> s = stripeFor(h);
        Node<K, V>[] t = s.snapshot();
        for (Node<K, V> n = t[bucketIndex(h, t.length)]; n != null; n = n.next) {
            if (n.hash == h && eq(n.key, key)) {
                return n;
            }
        }
        return null;
    }

    private Stripe<K, V> stripeFor(int h) {
        return stripes[h & (STRIPE_COUNT - 1)];
    }

    private static int bucketIndex(int h, int length) {
        return (h >>> STRIPE_BITS) & (length - 1);
    }

    private static int spread(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static boolean eq(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    private static void checkKey(Object k) {
        if (k == null) {
            throw new NullPointerException("key");
        }
    }

    private static void checkValue(Object v) {
        if (v == null) {
            throw new NullPointerException("value");
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newNodes(int n) {
        return (Node<K, V>[]) new Node[n];
    }
}
