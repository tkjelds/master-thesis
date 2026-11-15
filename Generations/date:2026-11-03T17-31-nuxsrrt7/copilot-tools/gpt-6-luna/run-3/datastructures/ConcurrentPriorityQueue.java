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

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            ensureCapacity();
            int index = size;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                E parentElement = elementAt(parent);
                if (element.compareTo(parentElement) >= 0) {
                    break;
                }
                heap[index] = parentElement;
                index = parent;
            }
            heap[index] = element;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    public boolean add(E element) {
        return offer(element);
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeRoot();
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeRoot();
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

    private E removeRoot() {
        E result = elementAt(0);
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
                if (right < newSize
                        && elementAt(right).compareTo(elementAt(child)) < 0) {
                    child = right;
                }

                E childElement = elementAt(child);
                if (((E) last).compareTo(childElement) <= 0) {
                    break;
                }
                heap[index] = childElement;
                index = child;
            }
            heap[index] = last;
        }
        return result;
    }

    private E elementAt(int index) {
        return (E) heap[index];
    }

    private void ensureCapacity() {
        if (size == heap.length) {
            int newCapacity = heap.length <= Integer.MAX_VALUE / 2
                    ? heap.length * 2
                    : Integer.MAX_VALUE;
            if (newCapacity == heap.length) {
                throw new OutOfMemoryError("priority queue is too large");
            }
            Object[] newHeap = new Object[newCapacity];
            System.arraycopy(heap, 0, newHeap, 0, size);
            heap = newHeap;
        }
    }
}
