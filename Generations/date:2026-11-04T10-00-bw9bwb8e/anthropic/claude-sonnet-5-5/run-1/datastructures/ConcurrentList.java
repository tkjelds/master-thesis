import java.lang.*;

/**
 * A thread-safe list backed by a growable array and guarded by a single
 * private monitor.
 *
 * Policy for operations that cannot complete immediately:
 *  - The list may be bounded (capacity given at construction) or effectively
 *    unbounded (default constructor).
 *  - add(E) / add(int, E) block while the list is full.
 *  - takeFirst() / takeLast() block while the list is empty.
 *  - tryAdd / pollFirst / pollLast are non-blocking variants that return
 *    false / null (as a failure indication) instead of waiting.
 *  - Index-based operations (get, set, remove(int)) throw
 *    IndexOutOfBoundsException for invalid indices, evaluated atomically
 *    with the operation. They never block.
 *  - Blocking operations throw InterruptedException if interrupted while
 *    waiting. All waits are guarded by while loops and use notifyAll.
 */
@ConcurrentList.ThreadSafe
public class ConcurrentList<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final int capacity;

    // All fields below are guarded by 'lock'.
    private Object[] elements;
    private int size;

    public ConcurrentList() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        int initial = capacity < 16 ? capacity : 16;
        this.elements = new Object[initial];
        this.size = 0;
    }

    // ---------- internal helpers (must be called holding lock) ----------

    private void ensureRoom() {
        if (size == elements.length) {
            long doubled = (long) elements.length * 2L;
            int newLen = (int) Math.min(doubled, (long) capacity);
            if (newLen <= elements.length) {
                newLen = elements.length + 1;
            }
            Object[] n = new Object[newLen];
            for (int i = 0; i < size; i++) {
                n[i] = elements[i];
            }
            elements = n;
        }
    }

    private void insertAt(int index, Object e) {
        ensureRoom();
        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
        }
        elements[index] = e;
        size++;
    }

    private Object removeAt(int index) {
        Object old = elements[index];
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
        }
        elements[size - 1] = null;
        size--;
        return old;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private int indexOfLocked(Object o) {
        for (int i = 0; i < size; i++) {
            if (eq(o, elements[i])) {
                return i;
            }
        }
        return -1;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    // ---------- adding ----------

    /** Appends element, blocking while the list is full. */
    public void add(E e) throws InterruptedException {
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            insertAt(size, e);
            lock.notifyAll();
        }
    }

    /** Inserts at index, blocking while full. Index validated after any wait. */
    public void add(int index, E e) throws InterruptedException {
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            insertAt(index, e);
            lock.notifyAll();
        }
    }

    /** Appends element if there is room; returns false if full. */
    public boolean tryAdd(E e) {
        synchronized (lock) {
            if (size >= capacity) {
                return false;
            }
            insertAt(size, e);
            lock.notifyAll();
            return true;
        }
    }

    // ---------- access ----------

    @SuppressWarnings("unchecked")
    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return (E) elements[index];
        }
    }

    @SuppressWarnings("unchecked")
    public E set(int index, E e) {
        synchronized (lock) {
            checkIndex(index);
            Object old = elements[index];
            elements[index] = e;
            return (E) old;
        }
    }

    public int indexOf(Object o) {
        synchronized (lock) {
            return indexOfLocked(o);
        }
    }

    public boolean contains(Object o) {
        synchronized (lock) {
            return indexOfLocked(o) >= 0;
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

    public int capacity() {
        return capacity;
    }

    // ---------- removing ----------

    @SuppressWarnings("unchecked")
    public E remove(int index) {
        synchronized (lock) {
            checkIndex(index);
            Object old = removeAt(index);
            lock.notifyAll();
            return (E) old;
        }
    }

    /** Removes first occurrence; returns true if removed. Does not block. */
    public boolean remove(Object o) {
        synchronized (lock) {
            int idx = indexOfLocked(o);
            if (idx < 0) {
                return false;
            }
            removeAt(idx);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns first element, blocking while empty. */
    @SuppressWarnings("unchecked")
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Object old = removeAt(0);
            lock.notifyAll();
            return (E) old;
        }
    }

    /** Removes and returns last element, blocking while empty. */
    @SuppressWarnings("unchecked")
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Object old = removeAt(size - 1);
            lock.notifyAll();
            return (E) old;
        }
    }

    /** Removes and returns first element, or null if empty. */
    @SuppressWarnings("unchecked")
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Object old = removeAt(0);
            lock.notifyAll();
            return (E) old;
        }
    }

    /** Removes and returns last element, or null if empty. */
    @SuppressWarnings("unchecked")
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Object old = removeAt(size - 1);
            lock.notifyAll();
            return (E) old;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    // ---------- snapshot ----------

    /** Returns an atomic snapshot of the contents. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = elements[i];
            }
            return copy;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(String.valueOf(elements[i]));
            }
            sb.append(']');
            return sb.toString();
        }
    }
}
