import java.lang.*;

@ConcurrentList.ThreadSafe
public class ConcurrentList<E> {
    @interface ThreadSafe {}


    // Policy:
    //  - The list may be bounded (capacity > 0) or effectively unbounded.
    //  - add(...) and addFirst(...) block while the list is full (bounded case).
    //  - takeFirst() / takeLast() block while the list is empty.
    //  - tryAdd(...), pollFirst() are non-blocking variants (return false / null on failure).
    //  - Index based operations (get, set, removeAt) never block;
    //    they throw IndexOutOfBoundsException for invalid indices.
    //  - All state is guarded by a single private monitor; waiters are woken with notifyAll.

    private final Object lock = new Object();
    private final int capacity;
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

    // ---- internal helpers (must be called while holding lock) ----

    private void ensureRoom() {
        if (size == elements.length) {
            long doubled = (long) elements.length * 2L;
            int newLen = (int) Math.min((long) capacity, Math.max(doubled, 1L));
            if (newLen > Integer.MAX_VALUE - 8) {
                newLen = Integer.MAX_VALUE - 8;
            }
            Object[] n = new Object[newLen];
            System.arraycopy(elements, 0, n, 0, size);
            elements = n;
        }
    }

    private void insertLocked(int index, E e) {
        ensureRoom();
        System.arraycopy(elements, index, elements, index + 1, size - index);
        elements[index] = e;
        size++;
    }

    private Object removeLocked(int index) {
        Object old = elements[index];
        int moved = size - index - 1;
        if (moved > 0) {
            System.arraycopy(elements, index + 1, elements, index, moved);
        }
        size--;
        elements[size] = null;
        return old;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
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

    // ---- public operations ----

    /** Appends element, blocking while the list is full. */
    public void add(E e) throws InterruptedException {
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            insertLocked(size, e);
            lock.notifyAll();
        }
    }

    /** Inserts at the front, blocking while the list is full. */
    public void addFirst(E e) throws InterruptedException {
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            insertLocked(0, e);
            lock.notifyAll();
        }
    }

    /**
     * Inserts at index, blocking while full. The index is validated against the
     * current size each time the lock is held.
     */
    public void add(int index, E e) throws InterruptedException {
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            while (size >= capacity) {
                lock.wait();
                if (index < 0 || index > size) {
                    throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
                }
            }
            insertLocked(index, e);
            lock.notifyAll();
        }
    }

    /** Non-blocking append; returns false if full. */
    public boolean tryAdd(E e) {
        synchronized (lock) {
            if (size >= capacity) {
                return false;
            }
            insertLocked(size, e);
            lock.notifyAll();
            return true;
        }
    }

    /** Atomically appends if not already present; blocks while full. Returns true if added. */
    public boolean addIfAbsent(E e) throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (indexOfLocked(e) >= 0) {
                    return false;
                }
                if (size < capacity) {
                    insertLocked(size, e);
                    lock.notifyAll();
                    return true;
                }
                lock.wait();
            }
        }
    }

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
            E old = (E) elements[index];
            elements[index] = e;
            return old;
        }
    }

    /** Removes and returns the element at index (non-blocking). */
    @SuppressWarnings("unchecked")
    public E removeAt(int index) {
        synchronized (lock) {
            checkIndex(index);
            E old = (E) removeLocked(index);
            lock.notifyAll();
            return old;
        }
    }

    /** Removes first occurrence of the object; returns true if removed. */
    public boolean remove(Object o) {
        synchronized (lock) {
            int i = indexOfLocked(o);
            if (i < 0) {
                return false;
            }
            removeLocked(i);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns the first element, blocking while empty. */
    @SuppressWarnings("unchecked")
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E e = (E) removeLocked(0);
            lock.notifyAll();
            return e;
        }
    }

    /** Removes and returns the last element, blocking while empty. */
    @SuppressWarnings("unchecked")
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E e = (E) removeLocked(size - 1);
            lock.notifyAll();
            return e;
        }
    }

    /** Non-blocking removal of first element; returns null if empty. */
    @SuppressWarnings("unchecked")
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E e = (E) removeLocked(0);
            lock.notifyAll();
            return e;
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    /** Returns a consistent snapshot of the contents. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] copy = new Object[size];
            System.arraycopy(elements, 0, copy, 0, size);
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
            sb.append("]");
            return sb.toString();
        }
    }
}
