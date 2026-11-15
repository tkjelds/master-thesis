@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] heap;
    private int size;

    public ConcurrentPriorityQueue() {
        this(16);
    }

    public ConcurrentPriorityQueue(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        heap = new Object[initialCapacity];
    }

    /**
     * Inserts an element. The queue is unbounded, so this operation does not wait
     * for capacity. Elements are ordered from smallest to largest.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            ensureCapacity();

            int target = size;
            while (target > 0) {
                int parent = (target - 1) / 2;
                if (element.compareTo(elementAt(parent)) >= 0) {
                    break;
                }
                target = parent;
            }

            int index = size;
            while (index > target) {
                int parent = (index - 1) / 2;
                heap[index] = heap[parent];
                index = parent;
            }
            heap[target] = element;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    public boolean add(E element) {
        return offer(element);
    }

    /**
     * Removes and returns the smallest element, or returns null if the queue is
     * empty.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeMinimum();
        }
    }

    /**
     * Removes and returns the smallest element, waiting until one is available.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeMinimum();
        }
    }

    /**
     * Returns the smallest element without removing it, or null if empty.
     */
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
        }
    }

    private E removeMinimum() {
        E minimum = elementAt(0);
        int oldSize = size;

        if (oldSize == 1) {
            heap[0] = null;
            size = 0;
            return minimum;
        }

        int newSize = oldSize - 1;
        E last = elementAt(newSize);

        // Determine the sift-down path before modifying the heap.
        int[] path = new int[64];
        int pathLength = 0;
        int index = 0;

        while (true) {
            int left = index * 2 + 1;
            if (left >= newSize) {
                break;
            }

            int right = left + 1;
            int child = left;
            if (right < newSize && elementAt(right).compareTo(elementAt(left)) < 0) {
                child = right;
            }

            if (elementAt(child).compareTo(last) >= 0) {
                break;
            }

            path[pathLength++] = child;
            index = child;
        }

        heap[newSize] = null;
        index = 0;
        for (int i = 0; i < pathLength; i++) {
            int child = path[i];
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = last;
        size = newSize;

        return minimum;
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int oldCapacity = heap.length;
        int newCapacity = oldCapacity <= Integer.MAX_VALUE / 2
                ? oldCapacity * 2
                : Integer.MAX_VALUE;

        if (newCapacity <= oldCapacity) {
            throw new OutOfMemoryError("Priority queue capacity exceeded");
        }

        Object[] larger = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            larger[i] = heap[i];
        }
        heap = larger;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }
}
