import java.lang.*;

@ConcurrentBoundedCollection.ThreadSafe
public class ConcurrentBoundedCollection<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;   // index of oldest element
    private int count;  // number of elements

    public ConcurrentBoundedCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.items = new Object[capacity];
        this.head = 0;
        this.count = 0;
    }

    // Blocks while the collection is full.
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements not permitted");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            items[(head + count) % items.length] = element;
            count++;
            lock.notifyAll();
        }
    }

    // Blocks while the collection is empty; removes and returns the oldest element.
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking add; returns false if full.
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("null elements not permitted");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            items[(head + count) % items.length] = element;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    // Non-blocking removal of oldest element; returns null if empty.
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking peek at oldest element; returns null if empty.
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (E) items[head];
        }
    }

    // Removes the first occurrence of the given element; returns true if removed.
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            int cap = items.length;
            for (int i = 0; i < count; i++) {
                if (o.equals(items[(head + i) % cap])) {
                    for (int j = i; j < count - 1; j++) {
                        items[(head + j) % cap] = items[(head + j + 1) % cap];
                    }
                    items[(head + count - 1) % cap] = null;
                    count--;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            int cap = items.length;
            for (int i = 0; i < count; i++) {
                if (o.equals(items[(head + i) % cap])) {
                    return true;
                }
            }
            return false;
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
        return items.length;
    }

    public void clear() {
        synchronized (lock) {
            int cap = items.length;
            for (int i = 0; i < count; i++) {
                items[(head + i) % cap] = null;
            }
            head = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    // Returns a consistent snapshot of the contents, oldest first.
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[count];
            int cap = items.length;
            for (int i = 0; i < count; i++) {
                result[i] = items[(head + i) % cap];
            }
            return result;
        }
    }
}
