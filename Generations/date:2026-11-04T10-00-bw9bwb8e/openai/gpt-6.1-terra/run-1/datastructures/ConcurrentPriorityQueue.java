/**
 * A bounded, thread-safe max-priority queue using natural ordering.
 *
 * Null elements are not permitted. Elements must maintain a stable,
 * consistent ordering while in the queue. Their compareTo methods must not
 * call back into this queue.
 *
 * offer returns false when full; poll and peek return null when empty.
 * put waits for space, and take waits for an element.
 */
@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] heap;
    private int size;

    public ConcurrentPriorityQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        heap = new Object[capacity];
    }

    public int capacity() {
        return heap.length;
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

    public E peek() {
        synchronized (monitor) {
            return size == 0 ? null : elementAt(0);
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E result = removeRoot();
            monitor.notifyAll();
            return result;
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E result = removeRoot();
            monitor.notifyAll();
            return result;
        }
    }

    public void clear() {
        synchronized (monitor) {
            if (size != 0) {
                for (int i = 0; i < size; i++) {
                    heap[i] = null;
                }
                size = 0;
                monitor.notifyAll();
            }
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }

    // Called with monitor held. Complete comparisons before modifying the heap.
    private void insert(E element) {
        int destination = size;
        while (destination > 0) {
            int parent = (destination - 1) / 2;
            if (element.compareTo(elementAt(parent)) <= 0) {
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

    // Called with monitor held. Complete comparisons before modifying the heap.
    private E removeRoot() {
        E result = elementAt(0);
        int newSize = size - 1;
        if (newSize == 0) {
            heap[0] = null;
            size = 0;
            return result;
        }

        E replacement = elementAt(newSize);
        int[] path = new int[32];
        int pathLength = 0;
        int index = 0;

        while (true) {
            long leftLong = 2L * index + 1;
            if (leftLong >= newSize) {
                break;
            }

            int child = (int) leftLong;
            int right = child + 1;
            if (right < newSize &&
                    elementAt(right).compareTo(elementAt(child)) > 0) {
                child = right;
            }
            if (replacement.compareTo(elementAt(child)) >= 0) {
                break;
            }
            path[pathLength++] = child;
            index = child;
        }

        index = 0;
        for (int i = 0; i < pathLength; i++) {
            int child = path[i];
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = replacement;
        heap[newSize] = null;
        size = newSize;
        return result;
    }
}
