@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}


    // Policy:
    //  - The stack is bounded (capacity fixed at construction).
    //  - push(E) blocks while the stack is full.
    //  - pop() blocks while the stack is empty.
    //  - tryPush / tryPop never block; they return false / null on failure.
    //  - peek() is non-blocking and returns null when empty.
    //  - Null elements are not permitted.
    //  - Blocking operations are interruptible (throw InterruptedException).

    private final Object lock = new Object();
    private final Object[] items;
    private int count; // guarded by lock

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.items = new Object[capacity];
        this.count = 0;
    }

    public ConcurrentStack() {
        this(Integer.MAX_VALUE > 1024 ? 1024 : 16);
    }

    public void push(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements are not allowed");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            items[count++] = element;
            lock.notifyAll();
        }
    }

    public boolean tryPush(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not allowed");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            items[count++] = element;
            lock.notifyAll();
            return true;
        }
    }

    public E pop() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E e = removeTop();
            lock.notifyAll();
            return e;
        }
    }

    public E tryPop() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E e = removeTop();
            lock.notifyAll();
            return e;
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (E) items[count - 1];
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

    public int capacity() {
        return items.length;
    }

    // Must be called while holding lock and with count > 0.
    @SuppressWarnings("unchecked")
    private E removeTop() {
        int idx = --count;
        E e = (E) items[idx];
        items[idx] = null; // avoid retaining reference
        return e;
    }
}
