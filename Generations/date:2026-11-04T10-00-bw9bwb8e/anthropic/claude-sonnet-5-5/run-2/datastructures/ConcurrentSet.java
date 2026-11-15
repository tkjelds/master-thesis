/**
 * A thread-safe set implemented as a hash table with separate chaining.
 *
 * Policy: every operation is non-blocking in the sense that it never waits for a
 * condition. add returns false if the element is already present, remove returns
 * false if the element is absent, and null elements are rejected with a
 * NullPointerException. All state is guarded by a single private monitor, so
 * each operation is atomic and the memory-visibility guarantees of
 * synchronized apply to all reads and writes.
 */
@ConcurrentSet.ThreadSafe
public class ConcurrentSet<E> {
    @interface ThreadSafe {}


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

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object lock = new Object();

    // All fields below are guarded by lock.
    private Node<E>[] table;
    private int size;
    private int threshold;

    public ConcurrentSet() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Negative capacity: " + initialCapacity);
        }
        int cap = 1;
        while (cap < initialCapacity && cap < MAX_CAPACITY) {
            cap <<= 1;
        }
        synchronized (lock) {
            table = newTable(cap);
            size = 0;
            threshold = (int) (cap * 0.75);
            if (threshold < 1) {
                threshold = 1;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Node<T>[] newTable(int capacity) {
        return (Node<T>[]) new Node[capacity];
    }

    private static int spread(Object o) {
        int h = o.hashCode();
        return h ^ (h >>> 16);
    }

    /** Adds the element if absent. Returns true if the set changed. */
    public boolean add(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        int hash = spread(element);
        synchronized (lock) {
            int idx = hash & (table.length - 1);
            for (Node<E> n = table[idx]; n != null; n = n.next) {
                if (n.hash == hash && (n.item == element || n.item.equals(element))) {
                    return false;
                }
            }
            table[idx] = new Node<E>(element, hash, table[idx]);
            size++;
            if (size > threshold) {
                resize();
            }
            return true;
        }
    }

    /** Removes the element if present. Returns true if the set changed. */
    public boolean remove(Object element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        int hash = spread(element);
        synchronized (lock) {
            int idx = hash & (table.length - 1);
            Node<E> prev = null;
            for (Node<E> n = table[idx]; n != null; prev = n, n = n.next) {
                if (n.hash == hash && (n.item == element || n.item.equals(element))) {
                    if (prev == null) {
                        table[idx] = n.next;
                    } else {
                        prev.next = n.next;
                    }
                    size--;
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns true if the element is in the set. */
    public boolean contains(Object element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        int hash = spread(element);
        synchronized (lock) {
            int idx = hash & (table.length - 1);
            for (Node<E> n = table[idx]; n != null; n = n.next) {
                if (n.hash == hash && (n.item == element || n.item.equals(element))) {
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    /** Returns an atomic snapshot of the elements as a new array. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int k = 0;
            for (int i = 0; i < table.length; i++) {
                for (Node<E> n = table[i]; n != null; n = n.next) {
                    result[k++] = n.item;
                }
            }
            return result;
        }
    }

    // Must be called with lock held.
    private void resize() {
        Node<E>[] old = table;
        int oldCap = old.length;
        if (oldCap >= MAX_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }
        int newCap = oldCap << 1;
        Node<E>[] fresh = newTable(newCap);
        for (int i = 0; i < oldCap; i++) {
            Node<E> n = old[i];
            while (n != null) {
                Node<E> next = n.next;
                int idx = n.hash & (newCap - 1);
                n.next = fresh[idx];
                fresh[idx] = n;
                n = next;
            }
        }
        table = fresh;
        threshold = (int) (newCap * 0.75);
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (int i = 0; i < table.length; i++) {
                for (Node<E> n = table[i]; n != null; n = n.next) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append(n.item);
                    first = false;
                }
            }
            return sb.append(']').toString();
        }
    }
}
