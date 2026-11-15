import java.lang.reflect.*;

/**
 * A thread-safe set implemented as a lock-striped hash table.
 *
 * Policy:
 * - The set is unbounded, so no operation ever needs to block waiting for
 *   space or for elements. add/remove/contains return immediately with a boolean result.
 * - Null elements are rejected with NullPointerException.
 * - Each element maps to exactly one stripe, and each stripe is guarded by its own
 *   intrinsic monitor. Single-element operations lock only one stripe.
 * - Whole-set operations (size, isEmpty, clear, toArray) acquire all stripe
 *   monitors in ascending index order, which gives an atomic snapshot and avoids deadlock.
 */
@ConcurrentSet.ThreadSafe
public class ConcurrentSet<E> {
    @interface ThreadSafe {}


    private static final int STRIPE_BITS = 4;
    private static final int STRIPES = 1 << STRIPE_BITS;
    private static final int INITIAL_BUCKETS = 8;

    private static final class Node<E> {
        final E item;
        final int hash;
        Node<E> next;

        Node(E item, int hash, Node<E> next) {
            this.item = item;
            this.hash = hash;
            this.next = next;
        }
    }

    /** Each stripe's fields are only accessed while holding the stripe's monitor. */
    private static final class Stripe<E> {
        Node<E>[] table;
        int count;

        @SuppressWarnings("unchecked")
        Stripe() {
            table = (Node<E>[]) new Node[INITIAL_BUCKETS];
            count = 0;
        }
    }

    private final Stripe<E>[] stripes;

    @SuppressWarnings("unchecked")
    public ConcurrentSet() {
        stripes = (Stripe<E>[]) new Stripe[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new Stripe<E>();
        }
    }

    private static int spread(Object o) {
        int h = o.hashCode();
        return h ^ (h >>> 16);
    }

    private static int stripeIndex(int hash) {
        return hash & (STRIPES - 1);
    }

    private static int bucketIndex(int hash, int length) {
        return (hash >>> STRIPE_BITS) & (length - 1);
    }

    private static void requireNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }

    /** Adds the element; returns true if it was not already present. */
    public boolean add(E e) {
        requireNonNull(e);
        int hash = spread(e);
        Stripe<E> s = stripes[stripeIndex(hash)];
        synchronized (s) {
            int idx = bucketIndex(hash, s.table.length);
            for (Node<E> n = s.table[idx]; n != null; n = n.next) {
                if (n.hash == hash && (n.item == e || n.item.equals(e))) {
                    return false;
                }
            }
            s.table[idx] = new Node<E>(e, hash, s.table[idx]);
            s.count++;
            if (s.count > (s.table.length >> 1) + (s.table.length >> 2)) {
                resize(s);
            }
            return true;
        }
    }

    /** Removes the element; returns true if it was present. */
    public boolean remove(Object o) {
        requireNonNull(o);
        int hash = spread(o);
        Stripe<E> s = stripes[stripeIndex(hash)];
        synchronized (s) {
            int idx = bucketIndex(hash, s.table.length);
            Node<E> prev = null;
            for (Node<E> n = s.table[idx]; n != null; prev = n, n = n.next) {
                if (n.hash == hash && (n.item == o || n.item.equals(o))) {
                    if (prev == null) {
                        s.table[idx] = n.next;
                    } else {
                        prev.next = n.next;
                    }
                    s.count--;
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns true if the element is present. */
    public boolean contains(Object o) {
        requireNonNull(o);
        int hash = spread(o);
        Stripe<E> s = stripes[stripeIndex(hash)];
        synchronized (s) {
            int idx = bucketIndex(hash, s.table.length);
            for (Node<E> n = s.table[idx]; n != null; n = n.next) {
                if (n.hash == hash && (n.item == o || n.item.equals(o))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Atomic size snapshot. */
    public int size() {
        return sizeLocked(0);
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Atomically removes all elements. */
    public void clear() {
        clearLocked(0);
    }

    /** Returns an atomic snapshot of the elements. */
    public Object[] toArray() {
        return toArrayLocked(0);
    }

    // ---- internal helpers ----

    @SuppressWarnings("unchecked")
    private void resize(Stripe<E> s) {
        // Caller holds the stripe's monitor.
        Node<E>[] old = s.table;
        int newLen = old.length << 1;
        Node<E>[] fresh = (Node<E>[]) new Node[newLen];
        for (int i = 0; i < old.length; i++) {
            Node<E> n = old[i];
            while (n != null) {
                Node<E> next = n.next;
                int idx = bucketIndex(n.hash, newLen);
                n.next = fresh[idx];
                fresh[idx] = n;
                n = next;
            }
        }
        s.table = fresh;
    }

    private int sizeLocked(int i) {
        if (i == STRIPES) {
            int total = 0;
            for (int j = 0; j < STRIPES; j++) {
                total += stripes[j].count;
            }
            return total;
        }
        synchronized (stripes[i]) {
            return sizeLocked(i + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private void clearLocked(int i) {
        if (i == STRIPES) {
            for (int j = 0; j < STRIPES; j++) {
                Stripe<E> s = stripes[j];
                s.table = (Node<E>[]) new Node[INITIAL_BUCKETS];
                s.count = 0;
            }
            return;
        }
        synchronized (stripes[i]) {
            clearLocked(i + 1);
        }
    }

    private Object[] toArrayLocked(int i) {
        if (i == STRIPES) {
            int total = 0;
            for (int j = 0; j < STRIPES; j++) {
                total += stripes[j].count;
            }
            Object[] result = new Object[total];
            int pos = 0;
            for (int j = 0; j < STRIPES; j++) {
                Node<E>[] table = stripes[j].table;
                for (int b = 0; b < table.length; b++) {
                    for (Node<E> n = table[b]; n != null; n = n.next) {
                        result[pos++] = n.item;
                    }
                }
            }
            return result;
        }
        synchronized (stripes[i]) {
            return toArrayLocked(i + 1);
        }
    }
}
