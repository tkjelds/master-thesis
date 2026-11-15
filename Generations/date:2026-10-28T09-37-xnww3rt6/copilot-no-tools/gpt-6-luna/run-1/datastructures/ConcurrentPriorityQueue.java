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
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        heap = new Object[initialCapacity];
    }

    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("null values are not supported");
        }
        synchronized (lock) {
            ensureCapacity();
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                E parentValue = elementAt(parent);
                if (value.compareTo(parentValue) >= 0) {
                    break;
                }
                heap[index] = parentValue;
                index = parent;
            }
            heap[index] = value;
            lock.notifyAll();
            return true;
        }
    }

    public boolean add(E value) {
        return offer(value);
    }

    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(0);
        }
    }

    public E poll() {
        synchronized (lock) {
            return size == 0 ? null : removeMinimum();
        }
    }

    public E remove() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("queue is empty");
            }
            return removeMinimum();
        }
    }

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

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }
        int newCapacity = heap.length + (heap.length >>> 1) + 1;
        if (newCapacity <= heap.length) {
            newCapacity = Integer.MAX_VALUE;
        }
        Object[] expanded = new Object[newCapacity];
        System.arraycopy(heap, 0, expanded, 0, size);
        heap = expanded;
    }

    private E removeMinimum() {
        E minimum = elementAt(0);
        int lastIndex = --size;
        Object last = heap[lastIndex];
        heap[lastIndex] = null;

        if (lastIndex > 0) {
            int index = 0;
            int half = size >>> 1;
            while (index < half) {
                int child = (index << 1) + 1;
                int right = child + 1;
                if (right < size
                        && elementAt(right).compareTo(elementAt(child)) < 0) {
                    child = right;
                }
                E childValue = elementAt(child);
                @SuppressWarnings("unchecked")
                E lastValue = (E) last;
                if (lastValue.compareTo(childValue) <= 0) {
                    break;
                }
                heap[index] = childValue;
                index = child;
            }
            heap[index] = last;
        }
        return minimum;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }
}
