@ConcurrentSet.ThreadSafe
public class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final Object monitor = new Object();
    private int size;

    public ConcurrentSet(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.elements = new Object[capacity];
    }

    public boolean add(E value) {
        return offer(value);
    }

    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("null values are not allowed");
        }

        synchronized (monitor) {
            if (containsInternal(value)) {
                return false;
            }

            while (size >= elements.length) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            int index = firstFreeIndex();
            if (index < 0) {
                return false;
            }

            elements[index] = value;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean remove(Object value) {
        if (value == null) {
            return false;
        }

        synchronized (monitor) {
            int index = indexOf(value);
            if (index < 0) {
                return false;
            }

            removeAt(index);
            monitor.notifyAll();
            return true;
        }
    }

    public E take() {
        synchronized (monitor) {
            while (size == 0) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            int index = firstFilledIndex();
            E value = cast(elements[index]);
            removeAt(index);
            monitor.notifyAll();
            return value;
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }

            int index = firstFilledIndex();
            E value = cast(elements[index]);
            removeAt(index);
            monitor.notifyAll();
            return value;
        }
    }

    public boolean contains(Object value) {
        if (value == null) {
            return false;
        }

        synchronized (monitor) {
            return containsInternal(value);
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

    public boolean isFull() {
        synchronized (monitor) {
            return size >= elements.length;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < elements.length; i++) {
                elements[i] = null;
            }
            size = 0;
            monitor.notifyAll();
        }
    }

    private boolean containsInternal(Object value) {
        for (int i = 0; i < elements.length; i++) {
            Object current = elements[i];
            if (current != null && equal(current, value)) {
                return true;
            }
        }
        return false;
    }

    private int indexOf(Object value) {
        for (int i = 0; i < elements.length; i++) {
            Object current = elements[i];
            if (current != null && equal(current, value)) {
                return i;
            }
        }
        return -1;
    }

    private int firstFreeIndex() {
        for (int i = 0; i < elements.length; i++) {
            if (elements[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private int firstFilledIndex() {
        for (int i = 0; i < elements.length; i++) {
            if (elements[i] != null) {
                return i;
            }
        }
        return -1;
    }

    private void removeAt(int index) {
        if (index < 0 || index >= elements.length) {
            return;
        }

        elements[index] = null;
        size--;

        for (int i = index; i < elements.length - 1; i++) {
            elements[i] = elements[i + 1];
        }
        elements[elements.length - 1] = null;
    }

    private boolean equal(Object left, Object right) {
        return left == right || (left != null && left.equals(right));
    }

    @SuppressWarnings("unchecked")
    private E cast(Object value) {
        return (E) value;
    }
}
