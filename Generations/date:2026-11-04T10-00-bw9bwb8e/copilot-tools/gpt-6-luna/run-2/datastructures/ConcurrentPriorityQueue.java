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
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        heap = new Object[initialCapacity];
    }

    public void offer(E value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }

        synchronized (lock) {
            ensureCapacity(size + 1);

            int destination = size;
            while (destination > 0) {
                int parent = (destination - 1) / 2;
                E parentValue = elementAt(parent);
                if (value.compareTo(parentValue) >= 0) {
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
            heap[destination] = value;
            size++;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

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

            while (index <= (newSize - 2) / 2) {
                int left = index * 2 + 1;
                int right = left + 1;
                int child = left;

                if (right < newSize && elementAt(right).compareTo(elementAt(left)) < 0) {
                    child = right;
                }
                if (replacement.compareTo(elementAt(child)) <= 0) {
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
            heap[parent] = replacement;
            heap[newSize] = null;
            size = newSize;
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
        }
    }

    private E elementAt(int index) {
        return (E) heap[index];
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= heap.length) {
            return;
        }

        int newCapacity = heap.length + (heap.length >> 1) + 1;
        if (newCapacity < requiredCapacity || newCapacity < 0) {
            newCapacity = requiredCapacity;
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }
}
