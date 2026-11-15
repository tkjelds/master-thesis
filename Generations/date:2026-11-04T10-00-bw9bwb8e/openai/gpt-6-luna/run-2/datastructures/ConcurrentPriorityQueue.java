@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private Object[] heap;
    private int size;

    public ConcurrentPriorityQueue() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentPriorityQueue(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        heap = new Object[initialCapacity];
    }

    /**
     * Inserts a non-null value. The queue is an unbounded min-heap, so this
     * operation does not wait for space.
     */
    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("Null values are not supported");
        }

        synchronized (this) {
            ensureCapacity(size + 1);

            int insertionIndex = size;
            while (insertionIndex > 0) {
                int parent = (insertionIndex - 1) >>> 1;
                E parentValue = elementAt(parent);
                if (parentValue.compareTo(value) <= 0) {
                    break;
                }
                insertionIndex = parent;
            }

            int index = size;
            while (index > insertionIndex) {
                int parent = (index - 1) >>> 1;
                heap[index] = heap[parent];
                index = parent;
            }
            heap[insertionIndex] = value;
            size++;
            return true;
        }
    }

    /**
     * Removes and returns the smallest value, or returns null if the queue is
     * empty. This operation never waits.
     */
    public E poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }

            int newSize = size - 1;
            E result = elementAt(0);
            E replacement = elementAt(newSize);

            if (newSize == 0) {
                heap[0] = null;
                size = 0;
                return result;
            }

            // Find the sift-down path before changing the heap.
            int[] path = new int[32];
            int pathLength = 0;
            int index = 0;
            int half = newSize >>> 1;

            while (index < half) {
                int child = (index << 1) + 1;
                int right = child + 1;

                if (right < newSize
                        && elementAt(right).compareTo(elementAt(child)) < 0) {
                    child = right;
                }

                if (elementAt(child).compareTo(replacement) >= 0) {
                    break;
                }

                path[pathLength++] = child;
                index = child;
            }

            heap[newSize] = null;
            size = newSize;

            int parent = 0;
            for (int i = 0; i < pathLength; i++) {
                int child = path[i];
                heap[parent] = heap[child];
                parent = child;
            }
            heap[parent] = replacement;

            return result;
        }
    }

    /**
     * Returns the smallest value without removing it, or null if the queue is
     * empty. This operation never waits.
     */
    public E peek() {
        synchronized (this) {
            return size == 0 ? null : elementAt(0);
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }

    private void ensureCapacity(int minimumCapacity) {
        if (minimumCapacity <= heap.length) {
            return;
        }
        if (minimumCapacity < 0 || minimumCapacity > MAX_ARRAY_SIZE) {
            throw new OutOfMemoryError("Priority queue capacity exceeded");
        }

        int oldCapacity = heap.length;
        int newCapacity = oldCapacity + (oldCapacity >>> 1) + 1;
        if (newCapacity < minimumCapacity || newCapacity < 0) {
            newCapacity = minimumCapacity;
        }
        if (newCapacity > MAX_ARRAY_SIZE) {
            newCapacity = MAX_ARRAY_SIZE;
        }

        Object[] newHeap = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            newHeap[i] = heap[i];
        }
        heap = newHeap;
    }
}
