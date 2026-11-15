@ConcurrentCollection.ThreadSafe
public class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final Object monitor;
    private int head;
    private int tail;
    private int size;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.elements = new Object[capacity];
        this.monitor = new Object();
    }

    public boolean add(E value) {
        return offer(value);
    }

    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (monitor) {
            while (size == elements.length) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for space", e);
                }
            }
            elements[tail] = value;
            tail = (tail + 1) % elements.length;
            size++;
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
                    throw new IllegalStateException("interrupted while waiting for an element", e);
                }
            }
            E value = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            monitor.notifyAll();
            return value;
        }
    }

    public E remove() {
        return take();
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E value = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            monitor.notifyAll();
            return value;
        }
    }

    public E peek() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            return (E) elements[head];
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int capacity() {
        synchronized (monitor) {
            return elements.length;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return elements.length - size;
        }
    }

    public boolean contains(Object value) {
        synchronized (monitor) {
            if (value == null) {
                return false;
            }
            for (int i = 0; i < size; i++) {
                Object current = elements[(head + i) % elements.length];
                if (value.equals(current)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < elements.length; i++) {
                elements[i] = null;
            }
            head = 0;
            tail = 0;
            size = 0;
            monitor.notifyAll();
        }
    }

    public Object[] toArray() {
        synchronized (monitor) {
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = elements[(head + i) % elements.length];
            }
            return copy;
        }
    }
}
