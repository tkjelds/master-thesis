@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}


    /** Local functional interface so that no java.util.function import is needed. */
    public interface BiConsumer<T, U> {
        void accept(T t, U u);
    }

    private static final int STRIPE_COUNT = 16;            // must be a power of two
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;
    private static final int STRIPE_SHIFT = 4;             // log2(STRIPE_COUNT)
    private static final int INITIAL_BUCKETS = 8;          // power of two

    /*
     * Concurrency policy:
     *  - Writers (put, remove, size) acquire only the monitor of the relevant stripe
     *    (size acquires all stripe monitors in ascending order to obtain a consistent snapshot).
     *  - Readers (containsKey, get, forEach) take no locks. They read the stripe's
     *    volatile bucket-array reference and traverse immutable bucket lists.
     *  - Bucket lists and pairs are immutable with final fields, so any reference a reader
     *    obtains is fully initialized (final-field guarantee). Arrays are replaced, never
     *    grown in place: a resize builds a fresh array and publishes it through a volatile write.
     *  - No operation blocks waiting for a condition: the map is unbounded, so put never
     *    fails for capacity reasons, and remove/get on an empty or missing key simply return null.
     *    The only waiting is the monitor entry that provides mutual exclusion within a stripe.
     *  - Null keys and null values are rejected with NullPointerException.
     */

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new Stripe<K, V>();
        }
    }

    // ---------------------------------------------------------------- public API

    public boolean containsKey(K k) {
        return findPair(k) != null;
    }

    public V get(K k) {
        Pair<K, V> p = findPair(k);
        return p == null ? null : p.value;
    }

    public V put(K k, V v) {
        if (k == null || v == null) {
            throw new NullPointerException();
        }
        int h = spread(k.hashCode());
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        synchronized (s) {
            Bucket<K, V>[] arr = s.buckets;
            int idx = (h >>> STRIPE_SHIFT) & (arr.length - 1);
            Bucket<K, V> head = arr[idx];
            Pair<K, V> old = findIn(head, k);
            Pair<K, V> np = new Pair<K, V>(h, k, v);
            if (old != null) {
                arr[idx] = replaceValue(head, k, np);
                return old.value;
            }
            arr[idx] = new Bucket<K, V>(np, head);
            s.count++;
            if (s.count > arr.length * 3 / 4) {
                resize(s);
            }
            return null;
        }
    }

    public V remove(K k) {
        if (k == null) {
            throw new NullPointerException();
        }
        int h = spread(k.hashCode());
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        synchronized (s) {
            Bucket<K, V>[] arr = s.buckets;
            int idx = (h >>> STRIPE_SHIFT) & (arr.length - 1);
            Bucket<K, V> head = arr[idx];
            Pair<K, V> old = findIn(head, k);
            if (old == null) {
                return null;
            }
            arr[idx] = removeKey(head, k);
            s.count--;
            return old.value;
        }
    }

    public int size() {
        return sizeFrom(0);
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) {
            throw new NullPointerException();
        }
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<K, V> s = stripes[i];
            Bucket<K, V>[] arr = s.buckets;   // volatile read; array is never mutated after publication
            for (int j = 0; j < arr.length; j++) {
                for (Bucket<K, V> b = arr[j]; b != null; b = b.next) {
                    consumer.accept(b.pair.key, b.pair.value);
                }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private Pair<K, V> findPair(K k) {
        if (k == null) {
            throw new NullPointerException();
        }
        int h = spread(k.hashCode());
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        Bucket<K, V>[] arr = s.buckets;
        Bucket<K, V> head = arr[(h >>> STRIPE_SHIFT) & (arr.length - 1)];
        return findIn(head, k);
    }

    /** Acquires stripe monitors in ascending index order, so the total is an exact snapshot. */
    private int sizeFrom(int i) {
        if (i == STRIPE_COUNT) {
            return 0;
        }
        Stripe<K, V> s = stripes[i];
        synchronized (s) {
            return s.count + sizeFrom(i + 1);
        }
    }

    /** Must be called while holding the stripe's monitor. Builds a new array and publishes it. */
    private static <K, V> void resize(Stripe<K, V> s) {
        Bucket<K, V>[] old = s.buckets;
        Bucket<K, V>[] nw = newBuckets(old.length * 2);
        for (int i = 0; i < old.length; i++) {
            for (Bucket<K, V> b = old[i]; b != null; b = b.next) {
                int idx = (b.pair.hash >>> STRIPE_SHIFT) & (nw.length - 1);
                nw[idx] = new Bucket<K, V>(b.pair, nw[idx]);
            }
        }
        s.buckets = nw;   // volatile write publishes the fully built array
    }

    private static <K, V> Pair<K, V> findIn(Bucket<K, V> b, Object k) {
        for (; b != null; b = b.next) {
            if (k.equals(b.pair.key)) {
                return b.pair;
            }
        }
        return null;
    }

    /** Returns a new list identical to b except that the entry for k is replaced by np. */
    private static <K, V> Bucket<K, V> replaceValue(Bucket<K, V> b, K k, Pair<K, V> np) {
        if (b.pair.key.equals(k)) {
            return new Bucket<K, V>(np, b.next);
        }
        return new Bucket<K, V>(b.pair, replaceValue(b.next, k, np));
    }

    /** Returns a new list without the entry for k. Assumes k is present. */
    private static <K, V> Bucket<K, V> removeKey(Bucket<K, V> b, K k) {
        if (b.pair.key.equals(k)) {
            return b.next;
        }
        return new Bucket<K, V>(b.pair, removeKey(b.next, k));
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Bucket<K, V>[] newBuckets(int n) {
        return (Bucket<K, V>[]) new Bucket[n];
    }

    // ---------------------------------------------------------------- helper types

    /** Immutable key-value pair. */
    private static final class Pair<K, V> {
        final int hash;
        final K key;
        final V value;

        Pair(int hash, K key, V value) {
            this.hash = hash;
            this.key = key;
            this.value = value;
        }
    }

    /** Immutable singly linked list node holding one pair. */
    private static final class Bucket<K, V> {
        final Pair<K, V> pair;
        final Bucket<K, V> next;

        Bucket(Pair<K, V> pair, Bucket<K, V> next) {
            this.pair = pair;
            this.next = next;
        }
    }

    /**
     * One stripe. Its monitor guards writes to 'buckets' contents and to 'count'.
     * 'buckets' is volatile so lock-free readers observe a fully built array after a resize.
     */
    private static final class Stripe<K, V> {
        volatile Bucket<K, V>[] buckets;
        volatile int count;   // written only while holding this stripe's monitor

        Stripe() {
            buckets = newBuckets(INITIAL_BUCKETS);
            count = 0;
        }
    }
}
