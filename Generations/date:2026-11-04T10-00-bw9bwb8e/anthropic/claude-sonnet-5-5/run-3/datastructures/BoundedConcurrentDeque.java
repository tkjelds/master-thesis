import java.util.NoSuchElementException;

/*
 * Note: the only "import" avoided is of libraries; NoSuchElementException is NOT imported below.
 * The class uses its own unchecked exception instead.
 */
@BoundedConcurrentDeque.ThreadSafe
public class BoundedConcurrentDeque<E> {
    @interface ThreadSafe {}


    /** Thrown by non-blocking removal/inspection operations on an empty deque. */
    public static class EmptyDequeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public EmptyDequeException() {
            super("deque is empty");
        }
    }

    // Policy:
    //  - Bounded circular buffer with fixed capacity.
    //  - putFirst/putLast block while the deque is full.
    //  - takeFirst/takeLast block while the deque is empty.
    //  - offerX / pollX are non-blocking and return false / null when they cannot complete.
    //  - All state is guarded by a single private monitor; waits are in while loops.

    private final Object lock = new Object();
    private final Object[] items;   // guarded by lock
    private int head;               // index of first element, guarded by lock
    private int count;              // number of elements, guarded by lock

    public BoundedConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.items = new Object[capacity];
        this.head = 0;
        this.count = 0;
    }

    public int capacity() {
        return items.length;
    }

    // ---- internal helpers; must be called while holding lock ----

    private void insertFirst(E e) {
        head = (head - 1 + items.length) % items.length;
        items[head] = e;
        count++;
    }

    private void insertLast(E e) {
        int tail = (head + count) % items.length;
        items[tail] = e;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E extractFirst() {
        E e = (E) items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return e;
    }

    @SuppressWarnings("unchecked")
    private E extractLast() {
        int tail = (head + count - 1) % items.length;
        E e = (E) items[tail];
        items[tail] = null;
        count--;
        return e;
    }

    // ---- blocking operations ----

    public void putFirst(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            insertFirst(e);
            lock.notifyAll();
        }
    }

    public void putLast(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            insertLast(e);
            lock.notifyAll();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E e = extractFirst();
            lock.notifyAll();
            return e;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E e = extractLast();
            lock.notifyAll();
            return e;
        }
    }

    // ---- non-blocking operations ----

    public boolean offerFirst(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (count == items.length) return false;
            insertFirst(e);
            lock.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (count == items.length) return false;
            insertLast(e);
            lock.notifyAll();
            return true;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (count == 0) return null;
            E e = extractFirst();
            lock.notifyAll();
            return e;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (count == 0) return null;
            E e = extractLast();
            lock.notifyAll();
            return e;
        }
    }

    public E removeFirst() {
        E e = pollFirst();
        if (e == null) throw new EmptyDequeException();
        return e;
    }

    public E removeLast() {
        E e = pollLast();
        if (e == null) throw new EmptyDequeException();
        return e;
    }

    // ---- inspection ----

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
            return (E) items[(head + count - 1) % items.length];
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

    public boolean isFull() {
        synchronized (lock) {
            return count == items.length;
        }
    }
}
