@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private Object[] heap;
    private int size;

    public ConcurrentPriorityQueue() {
        this(16);
    }

    public ConcurrentPriorityQueue(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must be non-negative");
        }
        heap = new Object[initialCapacity];
    }

    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (this) {
            if (size == heap.length) {
                grow();
            }

            int position = size;
            while (position > 0) {
                int parent = (position - 1) / 2;
                if (elementAt(parent).compareTo(value) <= 0) {
                    break;
                }
                position = parent;
            }

            for (int index = size; index > position; index = (index - 1) / 2) {
                heap[index] = heap[(index - 1) / 2];
            }
            heap[position] = value;
            size++;
            return true;
        }
    }

    public boolean add(E value) {
        return offer(value);
    }

    public E poll() {
        synchronized (this) {
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

            E tail = elementAt(newSize);
            int[] path = new int[32];
            int depth = 0;
            path[0] = 0;

            while (path[depth] < newSize / 2) {
                int child = path[depth] * 2 + 1;
                int right = child + 1;
                if (right < newSize
                        && elementAt(right).compareTo(elementAt(child)) < 0) {
                    child = right;
                }
                if (tail.compareTo(elementAt(child)) <= 0) {
                    break;
                }
                path[++depth] = child;
            }

            for (int index = 0; index < depth; index++) {
                heap[path[index]] = heap[path[index + 1]];
            }
            heap[path[depth]] = tail;
            heap[newSize] = null;
            size = newSize;
            return minimum;
        }
    }

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
            for (int index = 0; index < size; index++) {
                heap[index] = null;
            }
            size = 0;
        }
    }

    private void grow() {
        int oldCapacity = heap.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 1;
        } else if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] expanded = new Object[newCapacity];
        for (int index = 0; index < size; index++) {
            expanded[index] = heap[index];
        }
        heap = expanded;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }
}
