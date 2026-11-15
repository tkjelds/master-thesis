@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}


    // Policy:
    //  - The stack is bounded.
    //  - push(): blocks while the stack is full.
    //  - pop(): blocks while the stack is empty.
    //  - tryPush()/tryPop()/tryPeek(): never block; they report failure immediately
    //    (false / null).
    //  - Null elements are rejected, so null returned from tryPop/tryPeek always
    //    means "empty".
    //  - Blocking operations respond to interruption by throwing InterruptedException
    //    without changing the stack state.

    private final Object lock = new Object();
    private final Object[] elements;
    private int size; // guarded by lock

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.elements = new Object[capacity];
        this.size = 0;
    }

    public ConcurrentStack() {
        this(1024);
    }

    public void push(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[size++] = item;
            lock.notifyAll();
        }
    }

    public boolean tryPush(T item) {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            elements[size++] = item;
            lock.notifyAll();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public T pop() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T item = (T) elements[--size];
            elements[size] = null; // avoid retaining reference
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public T tryPop() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T item = (T) elements[--size];
            elements[size] = null;
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public T tryPeek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (T) elements[size - 1];
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
            return size == elements.length;
        }
    }

    public int capacity() {
        return elements.length;
    }
}
