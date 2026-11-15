/**
 * An unbounded, thread-safe max-priority queue.
 *
 * Elements are ordered by their natural ordering: the greatest element is
 * returned first. Null elements are not allowed. {@link #poll()} returns null
 * immediately when empty; {@link #take()} waits until an element is available.
 */
@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] heap = new Object[16];
    private int size;

    /**
     * Inserts an element.
     *
     * @return true
     * @throws NullPointerException if element is null
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            ensureCapacity();
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                E parentElement = elementAt(parent);
                if (element.compareTo(parentElement) <= 0) {
                    break;
                }
                heap[index] = parentElement;
                index = parent;
            }
            heap[index] = element;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Inserts an element. This queue is unbounded, so the method returns true
     * unless allocation fails.
     *
     * @throws NullPointerException if element is null
     */
    public boolean add(E element) {
        return offer(element);
    }

    /**
     * Removes and returns the greatest element, or null if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeTop();
        }
    }

    /**
     * Removes and returns the greatest element, waiting while the queue is
     * empty.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeTop();
        }
    }

    /**
     * Returns the greatest element without removing it, or null if empty.
     */
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(0);
        }
    }

    /** Returns the number of elements currently in the queue. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns whether the queue is currently empty. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Removes all elements currently in the queue. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
        }
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int oldCapacity = heap.length;
        if (oldCapacity >= Integer.MAX_VALUE - (oldCapacity >>> 1) - 1) {
            throw new OutOfMemoryError("Priority queue capacity exceeded");
        }
        int newCapacity = oldCapacity + (oldCapacity >>> 1) + 1;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }

    private E removeTop() {
        E result = elementAt(0);
        int newSize = --size;
        Object last = heap[newSize];
        heap[newSize] = null;

        if (newSize > 0) {
            siftDown(0, last);
        }
        return result;
    }

    private void siftDown(int index, Object value) {
        int half = size >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;

            if (right < size
                    && elementAt(right).compareTo(elementAt(child)) > 0) {
                child = right;
            }

            E childElement = elementAt(child);
            @SuppressWarnings("unchecked")
            E valueElement = (E) value;
            if (valueElement.compareTo(childElement) >= 0) {
                break;
            }

            heap[index] = childElement;
            index = child;
        }
        heap[index] = value;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }
}
