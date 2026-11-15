@ConcurrentBoundedCollection.ThreadSafe
public final class ConcurrentBoundedCollection<E> {
    @interface ThreadSafe {}


    /*
     * Policy summary:
     *  - Fixed capacity, FIFO ordering, null elements are rejected.
     *  - put(e) and take() block (via wait/notifyAll) while the collection is full or empty,
     *    respectively. They throw InterruptedException if the waiting thread is interrupted;
     *    the collection state is left unchanged in that case.
     *  - offer(e) and poll() never block. They return false / null immediately when the
     *    collection is full / empty.
     *  - All state is guarded by a single private monitor. Every read and write of the
     *    mutable fields happens while holding that monitor, which provides mutual exclusion,
     *    visibility, and safe publication of stored elements.
     *  - All waits are guarded by while loops that re-check the condition after waking.
     */

    private final Object lock = new Object();
    private final Object[] items;
    private int head;   // index of next element to remove
    private int tail;   // index of next free slot to insert into
    private int count;  // number of stored elements

    public ConcurrentBoundedCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /**
     * Inserts the element, blocking while the collection is full.
     */
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            enqueueLocked(element);
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the head element, blocking while the collection is empty.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E result = dequeueLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Inserts the element if there is room. Never blocks.
     *
     * @return true if inserted, false if the collection was full
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            enqueueLocked(element);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the head element if present. Never blocks.
     *
     * @return the head element, or null if the collection was empty
     */
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E result = dequeueLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Returns the head element without removing it, or null if empty. Never blocks.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (E) items[head];
        }
    }

    /**
     * Returns true if the collection contains an element equal to the given object.
     */
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            int index = head;
            for (int i = 0; i < count; i++) {
                if (o.equals(items[index])) {
                    return true;
                }
                index = (index + 1) % items.length;
            }
            return false;
        }
    }

    /**
     * Removes all elements and wakes any blocked producers.
     */
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
        // The array reference and its length are final, so no lock is needed for this read.
        return items.length;
    }

    // Caller must hold 'lock' and must have verified that count < items.length.
    private void enqueueLocked(E element) {
        items[tail] = element;
        tail = (tail + 1) % items.length;
        count++;
    }

    // Caller must hold 'lock' and must have verified that count > 0.
    @SuppressWarnings("unchecked")
    private E dequeueLocked() {
        E element = (E) items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return element;
    }
}
