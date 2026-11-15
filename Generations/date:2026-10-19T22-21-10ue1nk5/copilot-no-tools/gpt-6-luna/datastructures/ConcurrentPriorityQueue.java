@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;

    private final Object lock = new Object();
    private Object[] heap;
    private int size;

    public ConcurrentPriorityQueue() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentPriorityQueue(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        heap = new Object[initialCapacity];
    }

    /**
     * Adds an element to this unbounded min-priority queue.
     * Null elements are not supported.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            ensureCapacity();

            int destination = size;
            while (destination > 0) {
                int parent = (destination - 1) / 2;
                E parentValue = elementAt(parent);
                if (element.compareTo(parentValue) >= 0) {
                    break;
                }
                destination = parent;
            }

            for (int index = size; index > destination; ) {
                int parent = (index - 1) / 2;
                heap[index] = heap[parent];
                index = parent;
            }
            heap[destination] = element;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Returns and removes the smallest element, or null if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
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

    /**
     * Returns and removes the smallest element, waiting while the queue is empty.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeMinimum();
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
            for (int index = 0; index < size; index++) {
                heap[index] = null;
            }
            size = 0;
        }
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int newCapacity;
        if (heap.length > Integer.MAX_VALUE / 2) {
            if (heap.length == Integer.MAX_VALUE) {
                throw new OutOfMemoryError("Priority queue capacity exceeded");
            }
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = heap.length * 2;
        }

        Object[] newHeap = new Object[newCapacity];
        for (int index = 0; index < size; index++) {
            newHeap[index] = heap[index];
        }
        heap = newHeap;
    }

    private E removeMinimum() {
        if (size == 0) {
            return null;
        }

        E minimum = elementAt(0);
        if (size == 1) {
            heap[0] = null;
            size = 0;
            return minimum;
        }

        int newSize = size - 1;
        E replacement = elementAt(newSize);

        int destination = 0;
        while (true) {
            int left = destination * 2 + 1;
            if (left >= newSize) {
                break;
            }

            int right = left + 1;
            int smallerChild = left;
            if (right < newSize && elementAt(right).compareTo(elementAt(left)) < 0) {
                smallerChild = right;
            }

            if (replacement.compareTo(elementAt(smallerChild)) <= 0) {
                break;
            }
            destination = smallerChild;
        }

        int index = 0;
        while (index != destination) {
            int left = index * 2 + 1;
            int right = left + 1;
            int smallerChild = left;
            if (right < newSize && elementAt(right).compareTo(elementAt(left)) < 0) {
                smallerChild = right;
            }
            heap[index] = heap[smallerChild];
            index = smallerChild;
        }

        heap[destination] = replacement;
        heap[newSize] = null;
        size = newSize;
        return minimum;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }
}
