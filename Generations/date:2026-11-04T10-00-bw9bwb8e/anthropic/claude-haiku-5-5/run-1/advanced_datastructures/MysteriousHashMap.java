@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}


    private static final int STRIPE_BITS = 4;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;
    private static final int INITIAL_BUCKETS_PER_STRIPE = 4;
    private static final int MAX_BUCKETS_PER_STRIPE = 1 << 28;

    /** Immutable key-value pair. */
    private static final class Pair<K, V> {
        final K key;
        final V value;

        Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    /** Immutable singly linked list node. Chains are never mutated after publication. */
    private static final class Node<K, V> {
        final Pair<K, V> pair;
        final Node<K, V> next;

        Node(Pair<K, V> pair, Node<K, V> next) {
            this.pair = pair;
            this.next = next;
        }
    }

    /**
     * One stripe. Owns its own bucket array. The stripe object itself is the lock
     * guarding writes to {@code count} and all mutations of {@code table}.
     *
     * Publication protocol: a writer mutates the array (under the stripe lock) and then
     * performs a volatile write of {@code table}. Lock-free readers perform a volatile
     * read of {@code table} first and only then read bucket slots. Bucket chains are
     * immutable, so any Node a reader observes is fully constructed and safe to use.
     * Once a bigger array has been published, the old array is never written again.
     */
    private static final class Stripe<K, V> {
        volatile Node<K, V>[] table;
        int count; // guarded by this

        Stripe(Node<K, V>[] initialTable) {
            this.table = initialTable;
        }
    }

    /** Top-level array of stripes. Final, so the array reference is safely published. */
    private final Stripe<K, V>[] stripes;

    /**
     * Blocking policy: no operation waits on a condition. The only blocking is the
     * brief monitor acquisition of a single stripe lock. The map grows by reallocating
     * an individual stripe's bucket array, so put never fails for lack of capacity.
     * remove of an absent key returns null, and size of an empty map returns 0.
     * No wait/notify is needed because there is no bounded or empty-blocking state.
     */
    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        Stripe<K, V>[] s = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            s[i] = new Stripe<K, V>(newTable(INITIAL_BUCKETS_PER_STRIPE));
        }
        this.stripes = s;
    }

    // ------------------------------------------------------------------
    // Public operations
    // ------------------------------------------------------------------

    public boolean containsKey(K k) {
        return findPair(k) != null;
    }

    public V get(K k) {
        Pair<K, V> p = findPair(k);
        return p == null ? null : p.value;
    }

    public V put(K k, V v) {
        checkNotNull(k);
        checkNotNull(v);
        int h = spread(k.hashCode());
        Stripe<K, V> s = stripeFor(h);
        synchronized (s) {
            Node<K, V>[] t = s.table;
            int idx = bucketIndex(h, t.length);
            Node<K, V> head = t[idx];
            Pair<K, V> old = findPairInChain(head, k);
            if (old != null) {
                t[idx] = replaceInChain(head, k, new Pair<K, V>(k, v));
                s.table = t;
                return old.value;
            }
            t[idx] = new Node<K, V>(new Pair<K, V>(k, v), head);
            s.count++;
            if (s.count > t.length / 4 * 3 && t.length < MAX_BUCKETS_PER_STRIPE) {
                resize(s, t.length * 2);
            } else {
                s.table = t;
            }
            return null;
        }
    }

    public V remove(K k) {
        checkNotNull(k);
        int h = spread(k.hashCode());
        Stripe<K, V> s = stripeFor(h);
        synchronized (s) {
            Node<K, V>[] t = s.table;
            int idx = bucketIndex(h, t.length);
            Node<K, V> head = t[idx];
            Pair<K, V> old = findPairInChain(head, k);
            if (old == null) {
                return null;
            }
            t[idx] = removeFromChain(head, k);
            s.count--;
            s.table = t;
            return old.value;
        }
    }

    public int size() {
        int total = 0;
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<K, V> s = stripes[i];
            synchronized (s) {
                total += s.count;
            }
        }
        return total;
    }

    /**
     * Lock-free, weakly consistent traversal. Each stripe is read from a snapshot of
     * its current table reference. Consumers may observe concurrent updates.
     */
    public void forEach(java.util.function.BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<K, V> s = stripes[i];
            Node<K, V>[] t = s.table;
            for (int b = 0; b < t.length; b++) {
                for (Node<K, V> n = t[b]; n != null; n = n.next) {
                    consumer.accept(n.pair.key, n.pair.value);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /** Lock-free lookup used by containsKey and get. */
    private Pair<K, V> findPair(K k) {
        checkNotNull(k);
        int h = spread(k.hashCode());
        Stripe<K, V> s = stripeFor(h);
        Node<K, V>[] t = s.table;
        return findPairInChain(t[bucketIndex(h, t.length)], k);
    }

    private static <K, V> Pair<K, V> findPairInChain(Node<K, V> n, Object k) {
        for (; n != null; n = n.next) {
            if (n.pair.key.equals(k)) {
                return n.pair;
            }
        }
        return null;
    }

    /** Returns a new chain equal to n but with the first match of k replaced. Caller holds lock. */
    private static <K, V> Node<K, V> replaceInChain(Node<K, V> n, Object k, Pair<K, V> p) {
        if (n == null) {
            return null;
        }
        if (n.pair.key.equals(k)) {
            return new Node<K, V>(p, n.next);
        }
        return new Node<K, V>(n.pair, replaceInChain(n.next, k, p));
    }

    /** Returns a new chain equal to n but with the first match of k removed. Caller holds lock. */
    private static <K, V> Node<K, V> removeFromChain(Node<K, V> n, Object k) {
        if (n == null) {
            return null;
        }
        if (n.pair.key.equals(k)) {
            return n.next;
        }
        return new Node<K, V>(n.pair, removeFromChain(n.next, k));
    }

    /**
     * Reallocates one stripe's bucket array. Builds a fresh array, rehashes all pairs
     * into it, and then publishes it with a volatile write. The old array is never
     * modified afterwards. Caller holds the stripe lock.
     */
    private void resize(Stripe<K, V> s, int newLength) {
        Node<K, V>[] old = s.table;
        Node<K, V>[] fresh = newTable(newLength);
        for (int b = 0; b < old.length; b++) {
            for (Node<K, V> n = old[b]; n != null; n = n.next) {
                int idx = bucketIndex(spread(n.pair.key.hashCode()), newLength);
                fresh[idx] = new Node<K, V>(n.pair, fresh[idx]);
            }
        }
        s.table = fresh;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int length) {
        return (Node<K, V>[]) new Node[length];
    }

    private Stripe<K, V> stripeFor(int spreadHash) {
        return stripes[spreadHash & STRIPE_MASK];
    }

    /** Bucket index uses bits above the stripe-selection bits. */
    private static int bucketIndex(int spreadHash, int tableLength) {
        return (spreadHash >>> STRIPE_BITS) & (tableLength - 1);
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
    }
}
