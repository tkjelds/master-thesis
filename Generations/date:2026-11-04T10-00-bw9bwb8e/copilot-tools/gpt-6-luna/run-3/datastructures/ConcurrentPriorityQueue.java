@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentPriorityQueue() {
        this(16);
    }

    public ConcurrentPriorityQueue(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        elements = new Object[initialCapacity];
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
                elements[index] = parentElement;
                index = parent;
            }
            elements[index] = element;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    public boolean add(E element) {
        return offer(element);
    }

    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(0);
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

            E result = elementAt(0);
            int newSize = size - 1;
            E last = elementAt(newSize);
            elements[newSize] = null;
            size = newSize;

            if (newSize > 0) {
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

                    E childElement = elementAt(child);
                    if (last.compareTo(childElement) <= 0) {
                        break;
                    }

                    elements[index] = childElement;
                    index = child;
                }
                elements[index] = last;
            }

            return result;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return poll();
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
                elements[i] = null;
            }
            size = 0;
        }
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity <= Integer.MAX_VALUE / 2
                ? oldCapacity * 2
                : Integer.MAX_VALUE;
        if (newCapacity == oldCapacity) {
            throw new OutOfMemoryError("Priority queue capacity exceeded");
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
