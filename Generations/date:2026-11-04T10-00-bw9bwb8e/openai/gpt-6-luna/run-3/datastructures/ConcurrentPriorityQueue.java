@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<T extends Comparable<? super T>> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] heap;
    private int size;

    /** Creates an unbounded min-priority queue with an initial capacity of 16. */
    public ConcurrentPriorityQueue() {
        this(16);
    }

    /** Creates an unbounded min-priority queue with the given initial capacity. */
    public ConcurrentPriorityQueue(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        heap = new Object[Math.max(1, initialCapacity)];
    }

    /**
     * Adds a non-null value. This unbounded queue never waits for space.
     * Returns true after the value has been added.
     */
    public boolean offer(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            ensureCapacityLocked();
            int index = size;

            while (index > 0) {
                int parent = (index - 1) >>> 1;
                @SuppressWarnings("unchecked")
                T parentValue = (T) heap[parent];
                if (value.compareTo(parentValue) >= 0) {
                    break;
                }
                heap[index] = parentValue;
                index = parent;
            }

            heap[index] = value;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /** Adds a value; equivalent to offer. */
    public boolean add(T value) {
        return offer(value);
    }

    /**
     * Removes and returns the least value, or null if the queue is empty.
     * Null values are not permitted in this queue.
     */
    public T poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeRootLocked();
        }
    }

    /**
     * Removes and returns the least value, waiting until one is available.
     * If interrupted while waiting, this method throws InterruptedException.
     */
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeRootLocked();
        }
    }

    /** Removes and returns the least value, or throws if the queue is empty. */
    public T remove() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("queue is empty");
            }
            return removeRootLocked();
        }
    }

    /** Returns the least value without removing it, or null if the queue is empty. */
    public T peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            T value = (T) heap[0];
            return value;
        }
    }

    /** Returns the least value without removing it, or throws if the queue is empty. */
    public T element() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("queue is empty");
            }
            @SuppressWarnings("unchecked")
            T value = (T) heap[0];
            return value;
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

    private T removeRootLocked() {
        @SuppressWarnings("unchecked")
        T result = (T) heap[0];

        int newSize = size - 1;
        Object last = heap[newSize];
        heap[newSize] = null;
        size = newSize;

        if (newSize > 0) {
            int index = 0;
            int half = newSize >>> 1;

            while (index < half) {
                int child = (index << 1) + 1;
                int right = child + 1;

                if (right < newSize) {
                    @SuppressWarnings("unchecked")
                    T leftValue = (T) heap[child];
                    @SuppressWarnings("unchecked")
                    T rightValue = (T) heap[right];
                    if (rightValue.compareTo(leftValue) < 0) {
                        child = right;
                    }
                }

                @SuppressWarnings("unchecked")
                T childValue = (T) heap[child];
                @SuppressWarnings("unchecked")
                T lastValue = (T) last;
                if (lastValue.compareTo(childValue) <= 0) {
                    break;
                }

                heap[index] = childValue;
                index = child;
            }

            heap[index] = last;
        }

        return result;
    }

    private void ensureCapacityLocked() {
        if (size < heap.length) {
            return;
        }

        int oldCapacity = heap.length;
        if (oldCapacity >= Integer.MAX_VALUE / 2) {
            if (oldCapacity == Integer.MAX_VALUE) {
                throw new OutOfMemoryError("priority queue is too large");
            }
            heap = copyOf(heap, Integer.MAX_VALUE);
        } else {
            heap = copyOf(heap, oldCapacity * 2);
        }
    }

    private Object[] copyOf(Object[] source, int newLength) {
        Object[] copy = new Object[newLength];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
