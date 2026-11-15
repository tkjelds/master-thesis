@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;
    private int tail;
    private int count;
    private final int capacity;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    // Blocking: waits while full.
    public void addFirst(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            head = (head - 1 + capacity) % capacity;
            items[head] = e;
            count++;
            lock.notifyAll();
        }
    }

    // Blocking: waits while full.
    public void addLast(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            items[tail] = e;
            tail = (tail + 1) % capacity;
            count++;
            lock.notifyAll();
        }
    }

    // Blocking: waits while empty.
    @SuppressWarnings("unchecked")
    public E removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % capacity;
            count--;
            lock.notifyAll();
            return e;
        }
    }

    // Blocking: waits while empty.
    @SuppressWarnings("unchecked")
    public E removeLast() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            tail = (tail - 1 + capacity) % capacity;
            E e = (E) items[tail];
            items[tail] = null;
            count--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking: returns false if full.
    public boolean offerFirst(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (count == capacity) return false;
            head = (head - 1 + capacity) % capacity;
            items[head] = e;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    // Non-blocking: returns false if full.
    public boolean offerLast(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (count == capacity) return false;
            items[tail] = e;
            tail = (tail + 1) % capacity;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    // Non-blocking: returns null if empty.
    @SuppressWarnings("unchecked")
    public E pollFirst() {
        synchronized (lock) {
            if (count == 0) return null;
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % capacity;
            count--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking: returns null if empty.
    @SuppressWarnings("unchecked")
    public E pollLast() {
        synchronized (lock) {
            if (count == 0) return null;
            tail = (tail - 1 + capacity) % capacity;
            E e = (E) items[tail];
            items[tail] = null;
            count--;
            lock.notifyAll();
            return e;
        }
    }

    @SuppressWarnings("unchecked")
    public E peekFirst() {
        synchronized (lock) {
            if (count == 0) return null;
            return (E) items[head];
        }
    }

    @SuppressWarnings("unchecked")
    public E peekLast() {
        synchronized (lock) {
            if (count == 0) return null;
            return (E) items[(tail - 1 + capacity) % capacity];
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public int capacity() {
        return capacity;
    }
}
