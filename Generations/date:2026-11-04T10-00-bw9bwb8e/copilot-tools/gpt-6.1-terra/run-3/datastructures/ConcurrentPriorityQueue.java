@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final int[] heap;
    private int size;

    /**
     * Creates a bounded min-priority queue. Lower values have higher priority.
     */
    public ConcurrentPriorityQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        heap = new int[capacity];
    }

    /**
     * Adds a value, waiting while the queue is full.
     */
    public void put(int value) throws InterruptedException {
        synchronized (monitor) {
            while (size == heap.length) {
                monitor.wait();
            }

            int index = size++;
            while (index > 0) {
                int parent = (index - 1) / 2;
                if (heap[parent] <= value) {
                    break;
                }
                heap[index] = heap[parent];
                index = parent;
            }
            heap[index] = value;
            monitor.notifyAll();
        }
    }

    /**
     * Removes and returns the smallest value, waiting while the queue is empty.
     */
    public int take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }

            int result = heap[0];
            int last = heap[--size];

            if (size > 0) {
                int index = 0;
                while (index < size / 2) {
                    int child = index * 2 + 1;
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
            }

            monitor.notifyAll();
            return result;
        }
    }

    /**
     * Returns the smallest value without removing it, waiting while empty.
     */
    public int peek() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return heap[0];
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
}
