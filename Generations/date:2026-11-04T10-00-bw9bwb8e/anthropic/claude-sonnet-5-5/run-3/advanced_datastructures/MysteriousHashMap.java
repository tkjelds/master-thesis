@MysteriousHashMap.ThreadSafe
public class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}


    /** Local functional interface (no imports allowed), compatible with lambdas. */
    public interface BiConsumer<A, B> {
        void accept(A a, B b);
    }

    /** Immutable key-value pair that is also an immutable singly linked bucket list. */
    private static final class Node<K, V> {
        final K key;
        final V value;
        final int hash;
        final Node<K, V> next;

        Node(K key, V value, int hash, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }

    /** A stripe owns its own bucket array and its own size field. */
    private static final class Stripe<K, V> {
        // Both fields are volatile. Writers modify bucket elements under the stripe
        // lock and then perform a volatile write (count or table). Lock-free readers
        // read a volatile field first, so they see all prior bucket updates.
        volatile Node<K, V>[] table;
        volatile int count;

        // Padding to reduce false sharing between neighbouring Stripe objects.
        long p0, p1, p2, p3, p4, p5, p6;

        Stripe(int initialCapacity) {
            table = newTable(initialCapacity);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int n) {
        return (Node<K, V>[]) new Node[n];
    }

    private static final int STRIPE_BITS = 4;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;
    private static final int INITIAL_BUCKETS = 8;
    private static final int MAX_BUCKETS = 1 << 28;

    private final Stripe<K, V>[] stripes;

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        stripes = (Stripe<K, V>[]) new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new Stripe<K, V>(INITIAL_BUCKETS);
        }
    }

    private static int hash(Object k) {
        int h = k.hashCode();
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        return h;
    }

    private static int bucketIndex(int h, int length) {
        return (h >>> STRIPE_BITS) & (length - 1);
    }

    public boolean containsKey(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        int ignored = s.count;               // volatile read: establishes visibility
        Node<K, V>[] t = s.table;            // volatile read
        for (Node<K, V> n = t[bucketIndex(h, t.length)]; n != null; n = n.next) {
            if (n.hash == h && (n.key == k || n.key.equals(k))) return true;
        }
        return ignored < 0; // never true; keeps the volatile read live
    }

    public V get(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        int ignored = s.count;
        Node<K, V>[] t = s.table;
        for (Node<K, V> n = t[bucketIndex(h, t.length)]; n != null; n = n.next) {
            if (n.hash == h && (n.key == k || n.key.equals(k))) return n.value;
        }
        return null;
    }

    public V put(K k, V v) {
        if (k == null || v == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        synchronized (s) {
            Node<K, V>[] t = s.table;
            int idx = bucketIndex(h, t.length);
            Node<K, V> head = t[idx];

            Node<K, V> found = null;
            for (Node<K, V> n = head; n != null; n = n.next) {
                if (n.hash == h && (n.key == k || n.key.equals(k))) {
                    found = n;
                    break;
                }
            }

            if (found != null) {
                V old = found.value;
                // Rebuild prefix before the found node, replace the node.
                Node<K, V> rest = new Node<K, V>(found.key, v, h, found.next);
                t[idx] = copyPrefix(head, found, rest);
                s.count = s.count; // volatile write publishes the array update
                return old;
            }

            int c = s.count;
            if (c + 1 > (t.length >> 1) + (t.length >> 2) && t.length < MAX_BUCKETS) {
                t = resize(s, t);
                idx = bucketIndex(h, t.length);
                head = t[idx];
            }
            t[idx] = new Node<K, V>(k, v, h, head);
            s.count = c + 1; // volatile write publishes the array update
            return null;
        }
    }

    public V remove(K k) {
        if (k == null) throw new NullPointerException();
        int h = hash(k);
        Stripe<K, V> s = stripes[h & STRIPE_MASK];
        synchronized (s) {
            Node<K, V>[] t = s.table;
            int idx = bucketIndex(h, t.length);
            Node<K, V> head = t[idx];
            Node<K, V> found = null;
            for (Node<K, V> n = head; n != null; n = n.next) {
                if (n.hash == h && (n.key == k || n.key.equals(k))) {
                    found = n;
                    break;
                }
            }
            if (found == null) return null;
            t[idx] = copyPrefix(head, found, found.next);
            s.count = s.count - 1;
            return found.value;
        }
    }

    /** Copies nodes of the list from head up to (excluding) stop, followed by tail. */
    private static <K, V> Node<K, V> copyPrefix(Node<K, V> head, Node<K, V> stop, Node<K, V> tail) {
        if (head == stop) return tail;
        // Collect prefix length, then rebuild in order (iteratively, back to front).
        int len = 0;
        for (Node<K, V> n = head; n != stop; n = n.next) len++;
        @SuppressWarnings("unchecked")
        Node<K, V>[] prefix = (Node<K, V>[]) new Node[len];
        int i = 0;
        for (Node<K, V> n = head; n != stop; n = n.next) prefix[i++] = n;
        Node<K, V> result = tail;
        for (i = len - 1; i >= 0; i--) {
            Node<K, V> p = prefix[i];
            result = new Node<K, V>(p.key, p.value, p.hash, result);
        }
        return result;
    }

    /** Reallocates one stripe's bucket array; caller holds the stripe lock. */
    private Node<K, V>[] resize(Stripe<K, V> s, Node<K, V>[] old) {
        int newLen = old.length << 1;
        Node<K, V>[] nt = newTable(newLen);
        for (int i = 0; i < old.length; i++) {
            for (Node<K, V> n = old[i]; n != null; n = n.next) {
                int idx = bucketIndex(n.hash, newLen);
                nt[idx] = new Node<K, V>(n.key, n.value, n.hash, nt[idx]);
            }
        }
        s.table = nt; // volatile write publishes fully built new array
        return nt;
    }

    public int size() {
        long total = 0;
        for (int i = 0; i < STRIPE_COUNT; i++) {
            total += stripes[i].count;
        }
        if (total > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) total;
    }

    public void forEach(BiConsumer<K, V> consumer) {
        if (consumer == null) throw new NullPointerException();
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<K, V> s = stripes[i];
            int ignored = s.count;
            Node<K, V>[] t = s.table;
            for (int j = 0; j < t.length; j++) {
                for (Node<K, V> n = t[j]; n != null; n = n.next) {
                    consumer.accept(n.key, n.value);
                }
            }
        }
    }
}
