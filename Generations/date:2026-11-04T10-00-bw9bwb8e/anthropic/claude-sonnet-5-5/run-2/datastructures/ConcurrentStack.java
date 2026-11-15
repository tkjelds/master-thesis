@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}


    // Policy:
    //  - The stack is bounded (fixed capacity).
    //  - push(): blocks while the stack is full.
    //  - pop(): blocks while the stack is empty.
    //  - tryPush()/tryPop(): never block; return false/null immediately if the operation cannot complete.
    //  - Null elements are not permitted.
    //  - Blocking operations respond to interruption by throwing InterruptedException
    //    without changing the stack state.

    private final Object lock = new Object();
    private final Object[] items;
    private int count; // guarded by lock; also the index of the next free slot

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.items = new Object[capacity];
        this.count = 0;
    }

    public ConcurrentStack() {
        this(1024);
    }

    public int capacity() {
        return items.length; // immutable after construction
    }

    public void push(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item must not be null");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            items[count++] = item;
            lock.notifyAll();
        }
    }

    public boolean tryPush(T item) {
        if (item == null) {
            throw new NullPointerException("item must not be null");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            items[count++] = item;
            lock.notifyAll();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public T pop() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T item = (T) items[--count];
            items[count] = null;
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public T tryPop() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T item = (T) items[--count];
            items[count] = null;
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public T peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (T) items[count - 1];
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                items[i] = null;
            }
            count = 0;
            lock.notifyAll();
        }
    }
}
