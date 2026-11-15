@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] buffer;
    private final int capacity;
    private int head;
    private int tail;
    private int size;

    public ConcurrentDeque() {
        this(16);
    }

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    // Blocking: waits while the deque is full.
    public void addFirst(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            head = (head - 1 + capacity) % capacity;
            buffer[head] = e;
            size++;
            lock.notifyAll();
        }
    }

    // Blocking: waits while the deque is full.
    public void addLast(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            buffer[tail] = e;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
        }
    }

    // Blocking: waits while the deque is empty.
    @SuppressWarnings("unchecked")
    public E removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E e = (E) buffer[head];
            buffer[head] = null;
            head = (head + 1) % capacity;
            size--;
            lock.notifyAll();
            return e;
        }
    }

    // Blocking: waits while the deque is empty.
    @SuppressWarnings("unchecked")
    public E removeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            tail = (tail - 1 + capacity) % capacity;
            E e = (E) buffer[tail];
            buffer[tail] = null;
            size--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking: returns false if full.
    public boolean offerFirst(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (size == capacity) return false;
            head = (head - 1 + capacity) % capacity;
            buffer[head] = e;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    // Non-blocking: returns false if full.
    public boolean offerLast(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (size == capacity) return false;
            buffer[tail] = e;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    // Non-blocking: returns null if empty.
    @SuppressWarnings("unchecked")
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) return null;
            E e = (E) buffer[head];
            buffer[head] = null;
            head = (head + 1) % capacity;
            size--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking: returns null if empty.
    @SuppressWarnings("unchecked")
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) return null;
            tail = (tail - 1 + capacity) % capacity;
            E e = (E) buffer[tail];
            buffer[tail] = null;
            size--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking: returns null if empty.
    @SuppressWarnings("unchecked")
    public E peekFirst() {
        synchronized (lock) {
            if (size == 0) return null;
            return (E) buffer[head];
        }
    }

    // Non-blocking: returns null if empty.
    @SuppressWarnings("unchecked")
    public E peekLast() {
        synchronized (lock) {
            if (size == 0) return null;
            return (E) buffer[(tail - 1 + capacity) % capacity];
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

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }

    public int capacity() {
        return capacity;
    }
}
