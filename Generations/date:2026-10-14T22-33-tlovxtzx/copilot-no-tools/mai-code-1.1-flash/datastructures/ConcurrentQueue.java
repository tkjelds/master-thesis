@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object[] buffer;
    private final int capacity;
    private int head;
    private int tail;
    private int count;
    private final Object monitor;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be greater than zero");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        this.monitor = new Object();
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    public void put(E item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item is null");
        }

        synchronized (monitor) {
            while (count == capacity) {
                monitor.wait();
            }

            buffer[tail] = item;
            tail = (tail + 1) % capacity;
            count++;

            monitor.notifyAll();
        }
    }

    public boolean offer(E item) {
        if (item == null) {
            throw new NullPointerException("item is null");
        }

        synchronized (monitor) {
            if (count == capacity) {
                return false;
            }

            buffer[tail] = item;
            tail = (tail + 1) % capacity;
            count++;

            monitor.notifyAll();
            return true;
        }
    }

    public boolean offer(E item, long timeoutMillis) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item is null");
        }
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("timeoutMillis must be non-negative");
        }

        long startTime = System.currentTimeMillis();
        synchronized (monitor) {
            long remaining = timeoutMillis;

            while (count == capacity) {
                if (remaining <= 0L) {
                    return false;
                }

                long before = System.currentTimeMillis();
                monitor.wait(remaining);
                long elapsed = System.currentTimeMillis() - before;
                if (elapsed <= 0L) {
                    elapsed = 1L;
                }
                remaining -= elapsed;
            }

            buffer[tail] = item;
            tail = (tail + 1) % capacity;
            count++;

            monitor.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }

            @SuppressWarnings("unchecked")
            E item = (E) buffer[head];
            buffer[head] = null;
            head = (head + 1) % capacity;
            count--;

            monitor.notifyAll();
            return item;
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }

            @SuppressWarnings("unchecked")
            E item = (E) buffer[head];
            buffer[head] = null;
            head = (head + 1) % capacity;
            count--;

            monitor.notifyAll();
            return item;
        }
    }

    public E poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("timeoutMillis must be non-negative");
        }

        long startTime = System.currentTimeMillis();
        synchronized (monitor) {
            long remaining = timeoutMillis;

            while (count == 0) {
                if (remaining <= 0L) {
                    return null;
                }

                long before = System.currentTimeMillis();
                monitor.wait(remaining);
                long elapsed = System.currentTimeMillis() - before;
                if (elapsed <= 0L) {
                    elapsed = 1L;
                }
                remaining -= elapsed;
            }

            @SuppressWarnings("unchecked")
            E item = (E) buffer[head];
            buffer[head] = null;
            head = (head + 1) % capacity;
            count--;

            monitor.notifyAll();
            return item;
        }
    }

    public E peek() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }

            @SuppressWarnings("unchecked")
            E item = (E) buffer[head];
            return item;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return count == 0;
        }
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < capacity; i++) {
                buffer[i] = null;
            }
            head = 0;
            tail = 0;
            count = 0;
            monitor.notifyAll();
        }
    }
}
