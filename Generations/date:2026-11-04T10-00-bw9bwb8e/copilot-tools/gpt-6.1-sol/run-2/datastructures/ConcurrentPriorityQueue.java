/**
 * A bounded, thread-safe priority queue of int values.
 * Smaller values have higher priority. Duplicate values are allowed.
 *
 * put and take block while full or empty, respectively, and are interruptible.
 * offer returns false immediately when full.
 * peek and remove throw IllegalStateException immediately when empty.
 * Waiting threads have no fairness guarantee.
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

    public boolean isFull() {
        synchronized (monitor) {
            return size == heap.length;
        }
    }

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

    public void put(int value) throws InterruptedException {
        synchronized (monitor) {
            while (size == heap.length) {
                monitor.wait();
            }
            insert(value);
            monitor.notifyAll();
        }
    }

    public int peek() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("Queue is empty");
            }
            return heap[0];
        }
    }

    public int remove() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("Queue is empty");
            }
            int result = extractMinimum();
            monitor.notifyAll();
            return result;
        }
    }

    public int take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            int result = extractMinimum();
            monitor.notifyAll();
            return result;
        }
    }

    public void clear() {
        synchronized (monitor) {
            size = 0;
            monitor.notifyAll();
        }
    }

    // Called only while holding monitor and when space is available.
    private void insert(int value) {
        int index = size;
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (heap[parent] <= value) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = value;
        size++;
    }

    // Called only while holding monitor and when the queue is nonempty.
    private int extractMinimum() {
        int result = heap[0];
        int value = heap[--size];

        if (size > 0) {
            int index = 0;
            int firstLeaf = size / 2;

            while (index < firstLeaf) {
                int child = index * 2 + 1;
                int right = child + 1;

                if (right < size && heap[right] < heap[child]) {
                    child = right;
                }
                if (value <= heap[child]) {
                    break;
                }
                heap[index] = heap[child];
                index = child;
            }
            heap[index] = value;
        }

        return result;
    }
}
