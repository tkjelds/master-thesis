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
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        heap = new Object[initialCapacity];
    }

    /**
     * Inserts an element. Elements must remain comparison-stable while queued.
     * Null elements are not permitted.
     */
    public boolean offer(E element) {
        synchronized (lock) {
            if (element == null) {
                throw new NullPointerException("element");
            }

            ensureCapacity(size + 1);

            int index = size;
            int destination = index;
            while (destination > 0) {
                int parent = (destination - 1) >>> 1;
                if (element.compareTo(elementAt(parent)) >= 0) {
                    break;
                }
                destination = parent;
            }

            while (index > destination) {
                int parent = (index - 1) >>> 1;
                heap[index] = heap[parent];
                index = parent;
            }
            heap[destination] = element;
            size++;
            return true;
        }
    }

    public boolean add(E element) {
        return offer(element);
    }

    /**
     * Removes and returns the smallest element, or null if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

            E minimum = elementAt(0);
            int newSize = size - 1;
            if (newSize == 0) {
                heap[0] = null;
                size = 0;
                return minimum;
            }

            E replacement = elementAt(newSize);
            int[] path = new int[32];
            int pathLength = 0;
            int index = 0;

            while (true) {
                int left = (index << 1) + 1;
                if (left >= newSize) {
                    break;
                }

                int right = left + 1;
                int child = left;
                if (right < newSize
                        && elementAt(right).compareTo(elementAt(left)) < 0) {
                    child = right;
                }
                if (elementAt(child).compareTo(replacement) >= 0) {
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
            heap[index] = replacement;
            size = newSize;
            return minimum;
        }
    }

    /**
     * Returns the smallest element, or null if the queue is empty.
     */
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(0);
        }
    }

    /**
     * Removes and returns the smallest element, or throws if the queue is empty.
     */
    public E remove() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("queue is empty");
            }
            return poll();
        }
    }

    /**
     * Returns the smallest element, or throws if the queue is empty.
     */
    public E element() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("queue is empty");
            }
            return elementAt(0);
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

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= heap.length) {
            return;
        }

        long proposedCapacity = (long) heap.length + (heap.length >> 1) + 1;
        if (proposedCapacity < requiredCapacity) {
            proposedCapacity = requiredCapacity;
        }
        if (proposedCapacity > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("queue capacity exceeded");
        }

        Object[] expanded = new Object[(int) proposedCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }
}
