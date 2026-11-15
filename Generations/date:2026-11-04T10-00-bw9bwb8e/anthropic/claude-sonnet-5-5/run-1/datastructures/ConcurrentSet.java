@ConcurrentSet.ThreadSafe
public class ConcurrentSet<E> {
    @interface ThreadSafe {}


    /*
     * Thread-safe hash set using lock striping.
     *
     * Policy: no operation blocks. add/remove/contains return a boolean
     * immediately (add returns false if the element is already present,
     * remove returns false if it is absent). The set is unbounded.
     *
     * The set is split into independent segments, each guarded by its own
     * monitor. Single-element operations lock only one segment. Whole-set
     * operations (size, isEmpty, clear, toArray) lock all segments in a fixed
     * order (0..n-1), which avoids deadlock and gives an atomic snapshot.
     */

    private static final int SEGMENT_BITS = 4;
    private static final int SEGMENT_COUNT = 1 << SEGMENT_BITS;
    private static final int INITIAL_BUCKETS = 8;

    private static final int MODE_SIZE = 0;
    private static final int MODE_CLEAR = 1;
    private static final int MODE_TO_ARRAY = 2;

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

    private static final class Segment<E> {
        // All fields guarded by the segment's own monitor.
        Node<E>[] table;
        int count;

        @SuppressWarnings("unchecked")
        Segment() {
            table = (Node<E>[]) new Node[INITIAL_BUCKETS];
            count = 0;
        }
    }

    private final Segment<E>[] segments;

    @SuppressWarnings("unchecked")
    public ConcurrentSet() {
        segments = (Segment<E>[]) new Segment[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segments[i] = new Segment<E>();
        }
    }

    private static int spread(Object o) {
        int h = o.hashCode();
        return h ^ (h >>> 16);
    }

    private static int bucketIndex(int hash, int length) {
        return (hash >>> SEGMENT_BITS) & (length - 1);
    }

    private Segment<E> segmentFor(int hash) {
        return segments[hash & (SEGMENT_COUNT - 1)];
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }

    /** Adds the element; returns true if it was not already present. */
    public boolean add(E e) {
        checkNotNull(e);
        int hash = spread(e);
        Segment<E> seg = segmentFor(hash);
        synchronized (seg) {
            int idx = bucketIndex(hash, seg.table.length);
            for (Node<E> n = seg.table[idx]; n != null; n = n.next) {
                if (n.hash == hash && (n.item == e || n.item.equals(e))) {
                    return false;
                }
            }
            if (seg.count + 1 > (seg.table.length * 3) / 4) {
                resize(seg);
                idx = bucketIndex(hash, seg.table.length);
            }
            seg.table[idx] = new Node<E>(e, hash, seg.table[idx]);
            seg.count++;
            return true;
        }
    }

    /** Removes the element; returns true if it was present. */
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        int hash = spread(o);
        Segment<E> seg = segmentFor(hash);
        synchronized (seg) {
            int idx = bucketIndex(hash, seg.table.length);
            Node<E> prev = null;
            for (Node<E> n = seg.table[idx]; n != null; prev = n, n = n.next) {
                if (n.hash == hash && (n.item == o || n.item.equals(o))) {
                    if (prev == null) {
                        seg.table[idx] = n.next;
                    } else {
                        prev.next = n.next;
                    }
                    seg.count--;
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns true if the element is present. */
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        int hash = spread(o);
        Segment<E> seg = segmentFor(hash);
        synchronized (seg) {
            int idx = bucketIndex(hash, seg.table.length);
            for (Node<E> n = seg.table[idx]; n != null; n = n.next) {
                if (n.hash == hash && (n.item == o || n.item.equals(o))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns an atomic count of elements. */
    public int size() {
        return ((Integer) withAllLocks(0, MODE_SIZE)).intValue();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Removes all elements atomically. */
    public void clear() {
        withAllLocks(0, MODE_CLEAR);
    }

    /** Returns an atomic snapshot of the elements. */
    public Object[] toArray() {
        return (Object[]) withAllLocks(0, MODE_TO_ARRAY);
    }

    // Acquires all segment locks in index order, then performs the action.
    private Object withAllLocks(int i, int mode) {
        if (i == segments.length) {
            return doLocked(mode);
        }
        synchronized (segments[i]) {
            return withAllLocks(i + 1, mode);
        }
    }

    // Must be called with all segment locks held.
    @SuppressWarnings("unchecked")
    private Object doLocked(int mode) {
        if (mode == MODE_SIZE) {
            int total = 0;
            for (int i = 0; i < segments.length; i++) {
                total += segments[i].count;
            }
            return Integer.valueOf(total);
        } else if (mode == MODE_CLEAR) {
            for (int i = 0; i < segments.length; i++) {
                Segment<E> seg = segments[i];
                seg.table = (Node<E>[]) new Node[INITIAL_BUCKETS];
                seg.count = 0;
            }
            return null;
        } else {
            int total = 0;
            for (int i = 0; i < segments.length; i++) {
                total += segments[i].count;
            }
            Object[] result = new Object[total];
            int pos = 0;
            for (int i = 0; i < segments.length; i++) {
                Node<E>[] table = segments[i].table;
                for (int b = 0; b < table.length; b++) {
                    for (Node<E> n = table[b]; n != null; n = n.next) {
                        result[pos++] = n.item;
                    }
                }
            }
            return result;
        }
    }

    // Caller must hold seg's monitor.
    @SuppressWarnings("unchecked")
    private void resize(Segment<E> seg) {
        Node<E>[] old = seg.table;
        Node<E>[] fresh = (Node<E>[]) new Node[old.length * 2];
        for (int b = 0; b < old.length; b++) {
            Node<E> n = old[b];
            while (n != null) {
                Node<E> next = n.next;
                int idx = bucketIndex(n.hash, fresh.length);
                n.next = fresh[idx];
                fresh[idx] = n;
                n = next;
            }
        }
        seg.table = fresh;
    }
}
