/**
 * A bounded, thread-safe priority queue of int values.
 *
 * Smaller values have higher priority. Duplicate values are allowed.
 * All operations are serialized using a private intrinsic monitor.
 *
 * offer returns false when full; remove and peek throw when empty.
 * put and take block interruptibly until space or an element is available.
 * Waiting threads are not guaranteed fair ordering.
 */
@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final int[] heap;
    private int size;

    public ConcurrentPriorityQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        heap = new int[capacity];
    }

    public int capacity() {
        synchronized (monitor) {
            return heap.length;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return heap.length - size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == heap.length;
        }
    }

    /**
     * Inserts a value immediately, or returns false if the queue is full.
     */
    public boolean offer(int value) {
        synchronized (monitor) {
            if (size == heap.length) {
                return false;
            }
            insert(value);
            monitor.notifyAll();
            return true;
        }
    }

    /**
     * Waits for space and inserts the value.
     * If interrupted while waiting, leaves the queue unchanged.
     */
    public void put(int value) throws InterruptedException {
        synchronized (monitor) {
            while (size == heap.length) {
                monitor.wait();
            }
            insert(value);
            monitor.notifyAll();
        }
    }

    /**
     * Returns the smallest value without removing it.
     *
     * @throws IllegalStateException if the queue is empty
     */
    public int peek() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("Queue is empty");
            }
            return heap[0];
        }
    }

    /**
     * Removes and returns the smallest value immediately.
     *
     * @throws IllegalStateException if the queue is empty
     */
    public int remove() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("Queue is empty");
            }
            int result = removeMinimum();
            monitor.notifyAll();
            return result;
        }
    }

    /**
     * Waits for an element, then removes and returns the smallest value.
     * If interrupted while waiting, leaves the queue unchanged.
     */
    public int take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            int result = removeMinimum();
            monitor.notifyAll();
            return result;
        }
    }

    public void clear() {
        synchronized (monitor) {
            if (size != 0) {
                size = 0;
                monitor.notifyAll();
            }
        }
    }

    // Called only while holding monitor and with space available.
    private void insert(int value) {
        int index = size;

        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (heap[parent] <= value) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }

        heap[index] = value;
        size++;
    }

    // Called only while holding monitor and with an element available.
    private int removeMinimum() {
        int result = heap[0];
        int last = heap[--size];

        if (size == 0) {
            return result;
        }

        int index = 0;
        int firstLeaf = size >>> 1;

        while (index < firstLeaf) {
            int child = (index << 1) + 1;
            int right = child + 1;

            if (right < size && heap[right] < heap[child]) {
                child = right;
            }
            if (last <= heap[child]) {
                break;
            }

            heap[index] = heap[child];
            index = child;
        }

        heap[index] = last;
        return result;
    }
}
