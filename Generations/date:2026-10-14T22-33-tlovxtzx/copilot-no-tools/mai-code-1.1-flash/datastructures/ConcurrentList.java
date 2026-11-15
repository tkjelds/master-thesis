@ConcurrentList.ThreadSafe
public class ConcurrentList<T> {
    @interface ThreadSafe {}


    private final Object[] elements;
    private int head;
    private int tail;
    private int size;
    private final Object monitor;

    public ConcurrentList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.elements = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
        this.monitor = new Object();
    }

    public boolean add(T value) {
        put(value);
        return true;
    }

    public boolean offer(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (monitor) {
            if (size == elements.length) {
                return false;
            }
            enqueueLocked(value);
            monitor.notifyAll();
            return true;
        }
    }

    public void put(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (monitor) {
            while (size == elements.length) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting to add", e);
                }
            }
            enqueueLocked(value);
            monitor.notifyAll();
        }
    }

    public T take() {
        synchronized (monitor) {
            while (size == 0) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting to remove", e);
                }
            }
            T value = dequeueLocked();
            monitor.notifyAll();
            return value;
        }
    }

    public T remove() {
        return take();
    }

    public T poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            T value = dequeueLocked();
            monitor.notifyAll();
            return value;
        }
    }

    public T peek() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            return (T) elements[head];
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index out of range: " + index);
            }
            int actualIndex = (head + index) % elements.length;
            return (T) elements[actualIndex];
        }
    }

    public int indexOf(Object value) {
        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                int idx = (head + i) % elements.length;
                Object current = elements[idx];
                if (value == null) {
                    if (current == null) {
                        return i;
                    }
                } else if (value.equals(current)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public boolean contains(Object value) {
        return indexOf(value) >= 0;
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == elements.length;
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

    private void enqueueLocked(T value) {
        elements[tail] = value;
        tail = (tail + 1) % elements.length;
        size++;
    }

    private T dequeueLocked() {
        T value = (T) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        return value;
    }
}
