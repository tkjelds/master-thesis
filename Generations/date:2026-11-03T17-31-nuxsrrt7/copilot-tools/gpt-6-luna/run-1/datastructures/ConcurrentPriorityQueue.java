@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
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

    // The smallest element, according to compareTo, is removed first.
    // The queue grows as needed; null elements are not permitted.
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (monitor) {
            int insertion = size;
            while (insertion > 0) {
                int parent = (insertion - 1) >>> 1;
                @SuppressWarnings("unchecked")
                E parentElement = (E) heap[parent];
                if (element.compareTo(parentElement) >= 0) {
                    break;
                }
                insertion = parent;
            }

            ensureCapacity();
            int index = size;
            while (index > insertion) {
                int parent = (index - 1) >>> 1;
                heap[index] = heap[parent];
                index = parent;
            }
            heap[insertion] = element;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean add(E element) {
        return offer(element);
    }

    // Returns null immediately when the queue is empty.
    public E poll() {
        synchronized (monitor) {
            return pollLocked();
        }
    }

    // Waits until an element is available; interruption is propagated.
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return pollLocked();
        }
    }

    public E peek() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            E element = (E) heap[0];
            return element;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
        }
    }

    private E pollLocked() {
        if (size == 0) {
            return null;
        }

        @SuppressWarnings("unchecked")
        E result = (E) heap[0];

        int newSize = size - 1;
        Object last = heap[newSize];
        heap[newSize] = null;
        size = newSize;

        if (newSize > 0) {
            @SuppressWarnings("unchecked")
            E moved = (E) last;
            int index = 0;

            while (true) {
                int left = (index << 1) + 1;
                if (left >= newSize) {
                    break;
                }

                int right = left + 1;
                int child = left;
                if (right < newSize) {
                    @SuppressWarnings("unchecked")
                    E leftElement = (E) heap[left];
                    @SuppressWarnings("unchecked")
                    E rightElement = (E) heap[right];
                    if (rightElement.compareTo(leftElement) < 0) {
                        child = right;
                    }
                }

                @SuppressWarnings("unchecked")
                E childElement = (E) heap[child];
                if (childElement.compareTo(moved) >= 0) {
                    break;
                }

                heap[index] = heap[child];
                index = child;
            }

            heap[index] = moved;
        }

        return result;
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int oldCapacity = heap.length;
        int newCapacity = oldCapacity <= (Integer.MAX_VALUE - 8) / 2
                ? oldCapacity * 2
                : Integer.MAX_VALUE;
        if (newCapacity <= oldCapacity) {
            throw new OutOfMemoryError("Required array size too large");
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }
}
