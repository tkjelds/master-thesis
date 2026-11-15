/**
 * A bounded, thread-safe min-priority queue using natural ordering.
 *
 * Null elements are not permitted. Elements must maintain a stable, consistent
 * ordering while enqueued, and their compareTo methods must not mutate this queue.
 * Waiting operations do not guarantee fairness.
 */
@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] heap;
    private int size;

    public ConcurrentPriorityQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        heap = new Object[capacity];
    }

    /**
     * Inserts an element, or returns false if the queue is full.
     */
    public boolean offer(E element) {
        requireElement(element);
        synchronized (monitor) {
            if (size == heap.length) {
                return false;
            }
            insert(element);
            monitor.notifyAll();
            return true;
        }
    }

    /**
     * Waits for space and inserts an element.
     */
    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (monitor) {
            while (size == heap.length) {
                monitor.wait();
            }
            insert(element);
            monitor.notifyAll();
        }
    }

    /**
     * Removes and returns the least element, or null if the queue is empty.
     */
    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E result = removeMinimum();
            monitor.notifyAll();
            return result;
        }
    }

    /**
     * Waits for an element, then removes and returns the least element.
     */
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E result = removeMinimum();
            monitor.notifyAll();
            return result;
        }
    }

    /**
     * Returns the least element without removing it, or null if empty.
     */
    public E peek() {
        synchronized (monitor) {
            return size == 0 ? null : elementAt(0);
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int capacity() {
        return heap.length;
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }

    /* Called with monitor held. Comparisons precede all heap modifications. */
    private void insert(E element) {
        int destination = size;
        while (destination > 0) {
            int parent = (destination - 1) / 2;
            if (element.compareTo(elementAt(parent)) >= 0) {
                break;
            }
            destination = parent;
        }

        int index = size;
        while (index > destination) {
            int parent = (index - 1) / 2;
            heap[index] = heap[parent];
            index = parent;
        }
        heap[destination] = element;
        size++;
    }

    /* Called with monitor held. Comparisons precede all heap modifications. */
    private E removeMinimum() {
        E result = elementAt(0);
        int newSize = size - 1;
        if (newSize == 0) {
            heap[0] = null;
            size = 0;
            return result;
        }

        E last = elementAt(newSize);
        int[] path = new int[32];
        int pathLength = 0;
        int index = 0;

        while (index < newSize / 2) {
            int left = index * 2 + 1;
            int right = left + 1;
            int child = left;
            if (right < newSize && elementAt(right).compareTo(elementAt(left)) < 0) {
                child = right;
            }
            if (last.compareTo(elementAt(child)) <= 0) {
                break;
            }
            path[pathLength++] = child;
            index = child;
        }

        int parent = 0;
        for (int i = 0; i < pathLength; i++) {
            int child = path[i];
            heap[parent] = heap[child];
            parent = child;
        }
        heap[parent] = last;
        heap[newSize] = null;
        size = newSize;
        return result;
    }
}
