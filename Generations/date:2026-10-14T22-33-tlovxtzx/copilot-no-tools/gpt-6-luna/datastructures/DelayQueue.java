@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4L;

    private final Object lock = new Object();
    private Object[] heap = new Object[16];
    private int size;

    private static final class Entry<E> {
        final E value;
        final long deadlineNanos;

        Entry(E value, long deadlineNanos) {
            this.value = value;
            this.deadlineNanos = deadlineNanos;
        }
    }

    /**
     * Inserts an element that becomes available after the given delay.
     * The queue is unbounded, so this operation does not wait for capacity.
     *
     * @throws NullPointerException if value is null
     * @throws IllegalArgumentException if delayMillis is negative
     */
    public boolean offer(E value, long delayMillis) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }

        long delayNanos = delayMillis > MAX_DELAY_NANOS / NANOS_PER_MILLI
                ? MAX_DELAY_NANOS
                : delayMillis * NANOS_PER_MILLI;

        synchronized (lock) {
            ensureCapacity();
            Entry<E> entry = new Entry<E>(value, System.nanoTime() + delayNanos);
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                Entry<E> parentEntry = entryAt(parent);
                if (!comesBefore(entry, parentEntry)) {
                    break;
                }
                heap[index] = parentEntry;
                index = parent;
            }
            heap[index] = entry;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Inserts an element that becomes available after the given delay.
     * The queue is unbounded, so this operation never waits for capacity.
     *
     * @throws NullPointerException if value is null
     * @throws IllegalArgumentException if delayMillis is negative
     */
    public void put(E value, long delayMillis) {
        offer(value, delayMillis);
    }

    /**
     * Retrieves and removes the next available element, waiting while the
     * queue is empty or its earliest element has not reached its deadline.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            for (;;) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                Entry<E> first = entryAt(0);
                long remainingNanos = first.deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    E value = removeRoot().value;
                    lock.notifyAll();
                    return value;
                }

                long millis = remainingNanos / NANOS_PER_MILLI;
                int nanos = (int) (remainingNanos % NANOS_PER_MILLI);
                lock.wait(millis, nanos);
            }
        }
    }

    /**
     * Retrieves and removes the next available element, or returns null if
     * the queue is empty or its earliest element is not yet available.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0 || entryAt(0).deadlineNanos - System.nanoTime() > 0) {
                return null;
            }
            E value = removeRoot().value;
            lock.notifyAll();
            return value;
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int oldCapacity = heap.length;
        int newCapacity = oldCapacity + (oldCapacity >>> 1) + 1;
        if (newCapacity <= oldCapacity) {
            if (oldCapacity == Integer.MAX_VALUE) {
                throw new OutOfMemoryError("DelayQueue is too large");
            }
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] newHeap = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            newHeap[i] = heap[i];
        }
        heap = newHeap;
    }

    private Entry<E> entryAt(int index) {
        @SuppressWarnings("unchecked")
        Entry<E> entry = (Entry<E>) heap[index];
        return entry;
    }

    private Entry<E> removeRoot() {
        Entry<E> result = entryAt(0);
        int newSize = --size;
        Entry<E> last = entryAt(newSize);
        heap[newSize] = null;

        if (newSize == 0) {
            return result;
        }

        int index = 0;
        int half = newSize >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;
            Entry<E> childEntry = entryAt(child);

            if (right < newSize && comesBefore(entryAt(right), childEntry)) {
                child = right;
                childEntry = entryAt(right);
            }
            if (!comesBefore(childEntry, last)) {
                break;
            }
            heap[index] = childEntry;
            index = child;
        }
        heap[index] = last;
        return result;
    }

    private static boolean comesBefore(Entry<?> left, Entry<?> right) {
        return left.deadlineNanos - right.deadlineNanos < 0;
    }
}
