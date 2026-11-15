import java.lang.*;

/**
 * A thread-safe list backed by a growable array, guarded by a single intrinsic lock.
 *
 * Blocking policy:
 *  - The list may optionally be bounded. The default is effectively unbounded.
 *  - add(E), addFirst(E) and add(int, E) block while the list is full (bounded lists only).
 *  - removeFirst() and removeLast() block while the list is empty.
 *  - tryAdd, pollFirst and pollLast never block and report failure through their return value.
 *  - Index-based operations (get, set, remove(int)) never block and throw
 *    IndexOutOfBoundsException for an invalid index.
 *  - Blocking methods throw InterruptedException if the waiting thread is interrupted.
 *
 * All state is accessed only while holding the private lock, which also provides visibility.
 */
@ConcurrentList.ThreadSafe
public class ConcurrentList<E> {
    @interface ThreadSafe {}


    private static final int DEFAULT_INITIAL_CAPACITY = 10;

    private final Object lock = new Object();
    private final int maxCapacity;   // immutable bound; Integer.MAX_VALUE means unbounded
    private Object[] elements;       // guarded by lock
    private int size;                // guarded by lock

    public ConcurrentList() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentList(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be positive");
        }
        this.maxCapacity = maxCapacity;
        int initial = DEFAULT_INITIAL_CAPACITY;
        if (initial > maxCapacity) {
            initial = maxCapacity;
        }
        this.elements = new Object[initial];
        this.size = 0;
    }

    // ---------- internal helpers (call only while holding lock) ----------

    private boolean isFullLocked() {
        return size >= maxCapacity;
    }

    private void ensureCapacityLocked(int needed) {
        if (needed > elements.length) {
            long grown = (long) elements.length * 2L;
            if (grown < needed) {
                grown = needed;
            }
            if (grown > maxCapacity) {
                grown = maxCapacity;
            }
            Object[] bigger = new Object[(int) grown];
            System.arraycopy(elements, 0, bigger, 0, size);
            elements = bigger;
        }
    }

    private void insertLocked(int index, E element) {
        ensureCapacityLocked(size + 1);
        System.arraycopy(elements, index, elements, index + 1, size - index);
        elements[index] = element;
        size++;
    }

    @SuppressWarnings("unchecked")
    private E removeAtLocked(int index) {
        E old = (E) elements[index];
        int moved = size - index - 1;
        if (moved > 0) {
            System.arraycopy(elements, index + 1, elements, index, moved);
        }
        size--;
        elements[size] = null;
        return old;
    }

    private int indexOfLocked(Object o) {
        for (int i = 0; i < size; i++) {
            Object e = elements[i];
            if (o == null ? e == null : o.equals(e)) {
                return i;
            }
        }
        return -1;
    }

    private void checkIndexLocked(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    // ---------- adding ----------

    /** Appends the element, waiting while the list is full. */
    public void add(E element) throws InterruptedException {
        synchronized (lock) {
            while (isFullLocked()) {
                lock.wait();
            }
            insertLocked(size, element);
            lock.notifyAll();
        }
    }

    /** Inserts at the front, waiting while the list is full. */
    public void addFirst(E element) throws InterruptedException {
        synchronized (lock) {
            while (isFullLocked()) {
                lock.wait();
            }
            insertLocked(0, element);
            lock.notifyAll();
        }
    }

    /**
     * Inserts at the given index, waiting while the list is full.
     * The index is validated after space becomes available.
     */
    public void add(int index, E element) throws InterruptedException {
        synchronized (lock) {
            while (isFullLocked()) {
                lock.wait();
            }
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            insertLocked(index, element);
            lock.notifyAll();
        }
    }

    /** Appends the element if there is room; returns false if full. Never blocks. */
    public boolean tryAdd(E element) {
        synchronized (lock) {
            if (isFullLocked()) {
                return false;
            }
            insertLocked(size, element);
            lock.notifyAll();
            return true;
        }
    }

    // ---------- access ----------

    @SuppressWarnings("unchecked")
    public E get(int index) {
        synchronized (lock) {
            checkIndexLocked(index);
            return (E) elements[index];
        }
    }

    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        synchronized (lock) {
            checkIndexLocked(index);
            E old = (E) elements[index];
            elements[index] = element;
            return old;
        }
    }

    // ---------- removal ----------

    /** Removes the element at index; throws if the index is invalid. Never blocks. */
    public E remove(int index) {
        synchronized (lock) {
            checkIndexLocked(index);
            E old = removeAtLocked(index);
            lock.notifyAll();
            return old;
        }
    }

    /** Removes the first occurrence of the object; returns whether something was removed. */
    public boolean remove(Object o) {
        synchronized (lock) {
            int idx = indexOfLocked(o);
            if (idx < 0) {
                return false;
            }
            removeAtLocked(idx);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns the first element, waiting while the list is empty. */
    public E removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E e = removeAtLocked(0);
            lock.notifyAll();
            return e;
        }
    }

    /** Removes and returns the last element, waiting while the list is empty. */
    public E removeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E e = removeAtLocked(size - 1);
            lock.notifyAll();
            return e;
        }
    }

    /** Removes and returns the first element, or null if empty. Never blocks. */
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E e = removeAtLocked(0);
            lock.notifyAll();
            return e;
        }
    }

    /** Removes and returns the last element, or null if empty. Never blocks. */
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E e = removeAtLocked(size - 1);
            lock.notifyAll();
            return e;
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

    // ---------- queries ----------

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

    public boolean contains(Object o) {
        synchronized (lock) {
            return indexOfLocked(o) >= 0;
        }
    }

    public int indexOf(Object o) {
        synchronized (lock) {
            return indexOfLocked(o);
        }
    }

    public int capacityLimit() {
        return maxCapacity;
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
            sb.append(']');
            return sb.toString();
        }
    }
}
