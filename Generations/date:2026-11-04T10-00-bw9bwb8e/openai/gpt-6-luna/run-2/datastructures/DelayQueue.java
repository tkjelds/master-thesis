@DelayQueue.ThreadSafe
public final class DelayQueue<E extends DelayQueue.Delayed> {
    @interface ThreadSafe {}

    /**
     * Supplies the delay, in nanoseconds, used when an item is offered.
     * The queue snapshots this value at offer time; negative delays mean
     * the item is immediately available.
     */
    public interface Delayed {
        long getDelayNanos();
    }

    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4;

    private static final class Entry<T> {
        final T item;
        final long deadline;

        Entry(T item, long deadline) {
            this.item = item;
            this.deadline = deadline;
        }
    }

    private final Object lock = new Object();
    private Object[] heap = new Object[16];
    private int size;

    /**
     * Offers an item. This unbounded queue never waits for capacity.
     *
     * @return true
     * @throws IllegalArgumentException if item is null
     */
    public boolean offer(E item) {
        if (item == null) {
            throw new IllegalArgumentException("null items are not allowed");
        }

        long delay = item.getDelayNanos();
        if (delay < 0) {
            delay = 0;
        } else if (delay > MAX_DELAY_NANOS) {
            delay = MAX_DELAY_NANOS;
        }

        synchronized (lock) {
            ensureCapacity();
            Entry<E> entry = new Entry<E>(item, System.nanoTime() + delay);
            heap[size] = entry;
            siftUp(size);
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Adds an item without waiting. Provided as an alias for offer.
     */
    public void put(E item) {
        offer(item);
    }

    /**
     * Returns and removes the earliest item whose delay has expired, or null
     * if no item is currently available.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0 || remainingNanos(entryAt(0).deadline) > 0) {
                return null;
            }
            return removeHead();
        }
    }

    /**
     * Returns and removes the earliest item, waiting until one is available.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            for (;;) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                Entry<E> head = entryAt(0);
                long remaining = remainingNanos(head.deadline);
                if (remaining <= 0) {
                    return removeHead();
                }

                waitNanos(remaining);
            }
        }
    }

    /**
     * Waits up to timeoutMillis for an expired item.
     * A non-positive timeout performs an immediate poll.
     */
    public E poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0) {
            return poll();
        }

        long timeoutNanos;
        if (timeoutMillis > MAX_DELAY_NANOS / 1_000_000L) {
            timeoutNanos = MAX_DELAY_NANOS;
        } else {
            timeoutNanos = timeoutMillis * 1_000_000L;
        }
        long timeoutDeadline = System.nanoTime() + timeoutNanos;

        synchronized (lock) {
            for (;;) {
                if (size != 0) {
                    Entry<E> head = entryAt(0);
                    long itemRemaining = remainingNanos(head.deadline);
                    if (itemRemaining <= 0) {
                        return removeHead();
                    }

                    long timeoutRemaining = remainingNanos(timeoutDeadline);
                    if (timeoutRemaining <= 0) {
                        return null;
                    }
                    waitNanos(itemRemaining < timeoutRemaining
                            ? itemRemaining : timeoutRemaining);
                } else {
                    long timeoutRemaining = remainingNanos(timeoutDeadline);
                    if (timeoutRemaining <= 0) {
                        return null;
                    }
                    waitNanos(timeoutRemaining);
                }
            }
        }
    }

    /**
     * Returns the earliest item, whether or not its delay has expired.
     */
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : entryAt(0).item;
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
        if (heap.length >= Integer.MAX_VALUE / 2) {
            throw new OutOfMemoryError("queue is too large");
        }

        Object[] larger = new Object[heap.length * 2];
        for (int i = 0; i < size; i++) {
            larger[i] = heap[i];
        }
        heap = larger;
    }

    @SuppressWarnings("unchecked")
    private Entry<E> entryAt(int index) {
        return (Entry<E>) heap[index];
    }

    private void siftUp(int index) {
        Entry<E> entry = entryAt(index);
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            Entry<E> parentEntry = entryAt(parent);
            if (!before(entry.deadline, parentEntry.deadline)) {
                break;
            }
            heap[index] = parentEntry;
            index = parent;
        }
        heap[index] = entry;
    }

    private void siftDown(int index) {
        Entry<E> entry = entryAt(index);
        int half = size >>> 1;

        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;
            if (right < size
                    && before(entryAt(right).deadline, entryAt(child).deadline)) {
                child = right;
            }

            Entry<E> childEntry = entryAt(child);
            if (!before(childEntry.deadline, entry.deadline)) {
                break;
            }
            heap[index] = childEntry;
            index = child;
        }
        heap[index] = entry;
    }

    private E removeHead() {
        Entry<E> result = entryAt(0);
        int lastIndex = --size;
        if (lastIndex == 0) {
            heap[0] = null;
        } else {
            heap[0] = heap[lastIndex];
            heap[lastIndex] = null;
            siftDown(0);
        }
        lock.notifyAll();
        return result.item;
    }

    private static boolean before(long firstDeadline, long secondDeadline) {
        return firstDeadline - secondDeadline < 0;
    }

    private static long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private void waitNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        lock.wait(millis, extraNanos);
    }
}
