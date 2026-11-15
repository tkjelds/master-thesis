@ConcurrentStack.ThreadSafe
public class ConcurrentStack<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;
    private int size = 0;

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public ConcurrentStack() {
        this(1024);
    }

    public void push(E item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Null items are not allowed.");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            elements[size] = item;
            size++;
            lock.notifyAll();
        }
    }

    public boolean push(E item, long timeoutMillis) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Null items are not allowed.");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative: " + timeoutMillis);
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long deadline = (timeoutMillis > Long.MAX_VALUE - now) ? Long.MAX_VALUE : now + timeoutMillis;
            long remaining = timeoutMillis;

            while (size == capacity) {
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(remaining);
                remaining = deadline - System.currentTimeMillis();
            }

            elements[size] = item;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    public boolean tryPush(E item) {
        if (item == null) {
            throw new NullPointerException("Null items are not allowed.");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            elements[size] = item;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public E pop() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            size--;
            E item = (E) elements[size];
            elements[size] = null;
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public E pop(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative: " + timeoutMillis);
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long deadline = (timeoutMillis > Long.MAX_VALUE - now) ? Long.MAX_VALUE : now + timeoutMillis;
            long remaining = timeoutMillis;

            while (size == 0) {
                if (remaining <= 0) {
                    return null;
                }
                lock.wait(remaining);
                remaining = deadline - System.currentTimeMillis();
            }

            size--;
            E item = (E) elements[size];
            elements[size] = null;
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public E tryPop() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            size--;
            E item = (E) elements[size];
            elements[size] = null;
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return (E) elements[size - 1];
        }
    }

    @SuppressWarnings("unchecked")
    public E tryPeek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (E) elements[size - 1];
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }
}
