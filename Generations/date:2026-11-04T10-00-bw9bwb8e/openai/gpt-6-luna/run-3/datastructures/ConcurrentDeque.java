@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int count;

    /**
     * Creates a bounded deque with the given capacity.
     */
    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    /**
     * Inserts at the front, waiting while the deque is full.
     */
    public void putFirst(E element) throws InterruptedException {
        requireNonNull(element);
        synchronized (this) {
            while (count == elements.length) {
                wait();
            }
            head = head == 0 ? elements.length - 1 : head - 1;
            elements[head] = element;
            count++;
            notifyAll();
        }
    }

    /**
     * Inserts at the back, waiting while the deque is full.
     */
    public void putLast(E element) throws InterruptedException {
        requireNonNull(element);
        synchronized (this) {
            while (count == elements.length) {
                wait();
            }
            elements[indexForOffset(count)] = element;
            count++;
            notifyAll();
        }
    }

    /**
     * Removes and returns the front element, waiting while the deque is empty.
     */
    public E takeFirst() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            E result = elementAt(head);
            elements[head] = null;
            head = head + 1 == elements.length ? 0 : head + 1;
            count--;
            notifyAll();
            return result;
        }
    }

    /**
     * Removes and returns the back element, waiting while the deque is empty.
     */
    public E takeLast() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            int lastIndex = indexForOffset(count - 1);
            E result = elementAt(lastIndex);
            elements[lastIndex] = null;
            count--;
            notifyAll();
            return result;
        }
    }

    /**
     * Inserts at the front, returning false immediately if the deque is full.
     */
    public boolean offerFirst(E element) {
        requireNonNull(element);
        synchronized (this) {
            if (count == elements.length) {
                return false;
            }
            head = head == 0 ? elements.length - 1 : head - 1;
            elements[head] = element;
            count++;
            notifyAll();
            return true;
        }
    }

    /**
     * Inserts at the back, returning false immediately if the deque is full.
     */
    public boolean offerLast(E element) {
        requireNonNull(element);
        synchronized (this) {
            if (count == elements.length) {
                return false;
            }
            elements[indexForOffset(count)] = element;
            count++;
            notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the front element, or null if the deque is empty.
     */
    public E pollFirst() {
        synchronized (this) {
            if (count == 0) {
                return null;
            }
            E result = elementAt(head);
            elements[head] = null;
            head = head + 1 == elements.length ? 0 : head + 1;
            count--;
            notifyAll();
            return result;
        }
    }

    /**
     * Removes and returns the back element, or null if the deque is empty.
     */
    public E pollLast() {
        synchronized (this) {
            if (count == 0) {
                return null;
            }
            int lastIndex = indexForOffset(count - 1);
            E result = elementAt(lastIndex);
            elements[lastIndex] = null;
            count--;
            notifyAll();
            return result;
        }
    }

    /**
     * Returns the front element, or null if the deque is empty.
     */
    public E peekFirst() {
        synchronized (this) {
            return count == 0 ? null : elementAt(head);
        }
    }

    /**
     * Returns the back element, or null if the deque is empty.
     */
    public E peekLast() {
        synchronized (this) {
            return count == 0 ? null : elementAt(indexForOffset(count - 1));
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public int remainingCapacity() {
        synchronized (this) {
            return elements.length - count;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return count == elements.length;
        }
    }

    /**
     * Removes all elements and wakes threads waiting to insert.
     */
    public void clear() {
        synchronized (this) {
            for (int i = 0; i < count; i++) {
                elements[indexForOffset(i)] = null;
            }
            if (count != 0) {
                count = 0;
                head = 0;
                notifyAll();
            }
        }
    }

    private int indexForOffset(int offset) {
        int distanceToEnd = elements.length - head;
        return offset >= distanceToEnd ? offset - distanceToEnd : head + offset;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private void requireNonNull(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }
    }
}
