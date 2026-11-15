// Self-contained: java.util.function.BiConsumer cannot be imported, so a
// structurally identical interface is declared in this file.
interface BiConsumer<T, U> {
    void accept(T t, U u);
}

@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}


    private static final int STRIPE_COUNT = 16; // power of two
    private static final int INITIAL_BUCKETS = 8; // power of two, per stripe

    // Immutable key-value pair.
    private static final class Pair<K, V> {
        final K key;
        final V value;
        final int hash;

        Pair(K key, V value, int hash) {
            this.key = key;
            this.value = value;
            this.hash = hash;
        }
    }

    // Immutable singly linked list cell (bucket list).
    private static final class Cell<K, V> {
        final Pair<K, V> pair;
        final Cell<K, V> next;

        Cell(Pair<K, V> pair, Cell<K, V> next) {
            this.pair = pair;
            this.next = next;
        }
    }

    // Each stripe owns its own bucket array.
    private static final class Stripe<K, V> {
        // Padding to reduce false sharing between stripe objects.
        long p0, p1, p2, p3, p4, p5, p6;

        // Replaced (never mutated in size) on resize; volatile for publication.
        volatile Cell<K, V>[] buckets;
        // Written only while holding the stripe lock; volatile so lock-free
        // readers get a happens-before edge. Every update writes it after
        // modifying the bucket array.
        volatile int size;

        long q0, q1, q2, q3, q4, q5, q6;

        @SuppressWarnings("unchecked")
        Stripe() {
            buckets = (Cell<K, V>[]) new Cell[INITIAL_BUCKETS];
            size = 0;
        }
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
        return h ^ (h >>> 16);
    }

    private static int stripeIndex(int hash) {
        return hash & (STRIPE_COUNT - 1);
    }

    private static int bucketIndex(int hash, int length) {
        return (hash >>> 4) & (length - 1);
    }

    private static <K, V> Pair<K, V> find(Cell<K, V> c, Object key, int hash) {
        for (; c != null; c = c.next) {
            Pair<K, V> p = c.pair;
            if (p.hash == hash && (p.key == key || p.key.equals(key))) {
                return p;
            }
        }
        return null;
    }

    // Returns a list without the cell holding 'key' (which must be present).
    private static <K, V> Cell<K, V> without(Cell<K, V> head, Object key, int hash) {
        Cell<K, V> rev = null;
        Cell<K, V> c = head;
        while (c != null) {
            Pair<K, V> p = c.pair;
            if (p.hash == hash && (p.key == key || p.key.equals(key))) {
                break;
            }
            rev = new Cell<K, V>(p, rev);
            c = c.next;
        }
        if (c == null) {
            return head; // not present
        }
        Cell<K, V> result = c.next;
        for (Cell<K, V> r = rev; r != null; r = r.next) {
            result = new Cell<K, V>(r.pair, result);
        }
        return result;
    }

    public boolean containsKey(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[stripeIndex(h)];
        int ignored = s.size; // volatile read: see latest completed updates
        Cell<K, V>[] arr = s.buckets;
        return find(arr[bucketIndex(h, arr.length)], k, h) != null;
    }

    public V get(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[stripeIndex(h)];
        int ignored = s.size; // volatile read
        Cell<K, V>[] arr = s.buckets;
        Pair<K, V> p = find(arr[bucketIndex(h, arr.length)], k, h);
        return p == null ? null : p.value;
    }

    public V put(K k, V v) {
        if (k == null || v == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[stripeIndex(h)];
        synchronized (s) {
            Cell<K, V>[] arr = s.buckets;
            int idx = bucketIndex(h, arr.length);
            Cell<K, V> head = arr[idx];
            Pair<K, V> old = find(head, k, h);
            int newSize = s.size;
            Cell<K, V> base = head;
            if (old != null) {
                base = without(head, k, h);
            } else {
                newSize++;
            }
            arr[idx] = new Cell<K, V>(new Pair<K, V>(k, v, h), base);

            if (newSize > arr.length - (arr.length >> 2)) {
                resize(s, arr);
            }
            s.size = newSize; // volatile write publishes the element update
            return old == null ? null : old.value;
        }
    }

    // Called with the stripe lock held. Builds a new array, then publishes it.
    @SuppressWarnings("unchecked")
    private void resize(Stripe<K, V> s, Cell<K, V>[] old) {
        int newLen = old.length << 1;
        if (newLen <= 0 || newLen > (1 << 29)) {
            return; // cannot grow further
        }
        Cell<K, V>[] fresh = (Cell<K, V>[]) new Cell[newLen];
        for (int i = 0; i < old.length; i++) {
            for (Cell<K, V> c = old[i]; c != null; c = c.next) {
                int j = bucketIndex(c.pair.hash, newLen);
                fresh[j] = new Cell<K, V>(c.pair, fresh[j]);
            }
        }
        s.buckets = fresh; // volatile publication
    }

    public V remove(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[stripeIndex(h)];
        synchronized (s) {
            Cell<K, V>[] arr = s.buckets;
            int idx = bucketIndex(h, arr.length);
            Cell<K, V> head = arr[idx];
            Pair<K, V> old = find(head, k, h);
            if (old == null) {
                return null;
            }
            arr[idx] = without(head, k, h);
            s.size = s.size - 1; // volatile write publishes the removal
            return old.value;
        }
    }

    public int size() {
        long total = 0;
        for (int i = 0; i < STRIPE_COUNT; i++) {
            total += stripes[i].size;
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    // Lock-free, weakly consistent traversal.
    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) throw new NullPointerException();
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<K, V> s = stripes[i];
            int ignored = s.size; // volatile read
            Cell<K, V>[] arr = s.buckets;
            for (int j = 0; j < arr.length; j++) {
                for (Cell<K, V> c = arr[j]; c != null; c = c.next) {
                    consumer.accept(c.pair.key, c.pair.value);
                }
            }
        }
    }
}
