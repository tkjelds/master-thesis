@ConcurrentCollection.ThreadSafe
public class ConcurrentCollection<E> {
    @interface ThreadSafe {}


    // Policy:
    //  - put(e) blocks while the collection is full.
    //  - take() blocks while the collection is empty.
    //  - tryPut(e) / tryTake() never block and report failure via return value.
    //  - Elements are removed in FIFO order.
    //  - Null elements are rejected.
    // All state is guarded by the intrinsic monitor of 'lock'.

    private final Object lock = new Object();
    private final Object[] items;
    private int head;   // guarded by lock
    private int tail;   // guarded by lock
    private int count;  // guarded by lock

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.items = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements not permitted");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            enqueue(element);
            lock.notifyAll();
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E e = dequeue();
            lock.notifyAll();
            return e;
        }
    }

    public boolean tryPut(E element) {
        if (element == null) {
            throw new NullPointerException("null elements not permitted");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            enqueue(element);
            lock.notifyAll();
            return true;
        }
    }

    // Returns null if the collection is empty.
    public E tryTake() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E e = dequeue();
            lock.notifyAll();
            return e;
        }
    }

    // Returns the next element without removing it, or null if empty.
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (E) items[head];
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public int capacity() {
        return items.length;
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
            for (int i = 0; i < items.length; i++) {
                items[i] = null;
            }
            head = 0;
            tail = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    // Returns a consistent snapshot of the contents in FIFO order.
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[count];
            for (int i = 0; i < count; i++) {
                result[i] = items[(head + i) % items.length];
            }
            return result;
        }
    }

    // Must be called while holding lock.
    private void enqueue(E element) {
        items[tail] = element;
        tail = (tail + 1) % items.length;
        count++;
    }

    // Must be called while holding lock.
    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return e;
    }
}
