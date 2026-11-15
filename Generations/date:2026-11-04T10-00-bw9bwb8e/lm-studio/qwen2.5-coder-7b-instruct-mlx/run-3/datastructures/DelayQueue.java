@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}

    private static final long INITIAL_DELAY = 1000; // Initial delay in milliseconds
    private static final int MAX_CAPACITY = 100; // Maximum capacity of the queue

    private T[] elements;
    private int size;
    private Object lock = new Object();

    @SuppressWarnings("unchecked")
    public DelayQueue() {
        elements = (T[]) new Object[MAX_CAPACITY];
        size = 0;
    }

    public void put(T element) throws InterruptedException {
        synchronized (lock) {
            while (size == MAX_CAPACITY) {
                lock.wait();
            }
            elements[size++] = element;
            lock.notifyAll();
        }
    }

    public T take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T element = elements[0];
            System.arraycopy(elements, 1, elements, 0, --size);
            lock.notifyAll();
            return element;
        }
    }

    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        synchronized (lock) {
            while (size == MAX_CAPACITY) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = lock.wait(nanos);
            }
            elements[size++] = element;
            lock.notifyAll();
            return true;
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        synchronized (lock) {
            while (size == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = lock.wait(nanos);
            }
            T element = elements[0];
            System.arraycopy(elements, 1, elements, 0, --size);
            lock.notifyAll();
            return element;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; ++i) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    public T peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return elements[0];
        }
    }
}
