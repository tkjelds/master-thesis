@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

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

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (this) {
            ensureCapacity();
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) / 2;
                E parentValue = valueAt(parent);
                if (parentValue.compareTo(element) <= 0) {
                    break;
                }
                heap[index] = parentValue;
                index = parent;
            }
            heap[index] = element;
            notifyAll();
            return true;
        }
    }

    public E poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return removeMinimum();
        }
    }

    public E take() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            return removeMinimum();
        }
    }

    public E peek() {
        synchronized (this) {
            return size == 0 ? null : valueAt(0);
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

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }
        if (heap.length == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Priority queue is too large");
        }

        int newCapacity = heap.length <= Integer.MAX_VALUE / 2
                ? heap.length * 2
                : Integer.MAX_VALUE;
        Object[] expanded = new Object[newCapacity];
        System.arraycopy(heap, 0, expanded, 0, size);
        heap = expanded;
    }

    private E removeMinimum() {
        E minimum = valueAt(0);
        E last = valueAt(--size);
        heap[size] = null;

        if (size > 0) {
            int index = 0;
            int half = size / 2;
            while (index < half) {
                int child = index * 2 + 1;
                int right = child + 1;
                if (right < size && valueAt(right).compareTo(valueAt(child)) < 0) {
                    child = right;
                }

                E childValue = valueAt(child);
                if (last.compareTo(childValue) <= 0) {
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
    private E valueAt(int index) {
        return (E) heap[index];
    }
}
