/**
 * A thread-safe, bounded min-priority queue.
 *
 * Elements must be non-null and have a stable, mutually consistent natural
 * ordering. The default capacity is Integer.MAX_VALUE; storage grows as needed.
 *
 * offer returns false when full; put waits for space.
 * poll and peek return null when empty; take waits for an element.
 * Waiting operations propagate InterruptedException.
 */
@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;
    private Object[] heap;
    private int size;

    public ConcurrentPriorityQueue() {
        this(2147483647);
    }

    public ConcurrentPriorityQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.heap = new Object[capacity < 16 ? capacity : 16];
    }

    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            insert(element);
            lock.notifyAll();
            return true;
        }
    }

    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            insert(element);
            lock.notifyAll();
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = removeMin();
            lock.notifyAll();
            return result;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = removeMin();
            lock.notifyAll();
            return result;
        }
    }

    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(0);
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

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                for (int i = 0; i < size; i++) {
                    heap[i] = null;
                }
                size = 0;
                lock.notifyAll();
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

    /* Called only while holding lock. */
    private void ensureSpace() {
        if (size < heap.length) {
            return;
        }
        long doubled = (long) heap.length * 2;
        int newLength = doubled > capacity ? capacity : (int) doubled;
        Object[] expanded = new Object[newLength];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }

    /* Determine the insertion position before modifying the heap. */
    private void insert(E element) {
        ensureSpace();

        int target = size;
        while (target > 0) {
            int parent = (target - 1) / 2;
            if (element.compareTo(elementAt(parent)) >= 0) {
                break;
            }
            target = parent;
        }

        int position = size;
        while (position > target) {
            int parent = (position - 1) / 2;
            heap[position] = heap[parent];
            position = parent;
        }
        heap[target] = element;
        size++;
    }

    /* Determine the removal path before modifying the heap. */
    private E removeMin() {
        E result = elementAt(0);
        int newSize = size - 1;
        if (newSize == 0) {
            heap[0] = null;
            size = 0;
            return result;
        }

        E last = elementAt(newSize);
        int target = 0;
        while (target < newSize / 2) {
            int left = target * 2 + 1;
            int right = left + 1;
            int smaller = left;
            if (right < newSize
                    && elementAt(right).compareTo(elementAt(left)) < 0) {
                smaller = right;
            }
            if (last.compareTo(elementAt(smaller)) <= 0) {
                break;
            }
            target = smaller;
        }

        // The bits of target + 1 encode the path from the root.
        int path = target + 1;
        int bit = 1;
        while (bit <= path / 2) {
            bit <<= 1;
        }
        bit >>>= 1;

        int position = 0;
        while (bit != 0) {
            int child = position * 2 + 1;
            if ((path & bit) != 0) {
                child++;
            }
            heap[position] = heap[child];
            position = child;
            bit >>>= 1;
        }

        heap[target] = last;
        heap[newSize] = null;
        size = newSize;
        return result;
    }
}
