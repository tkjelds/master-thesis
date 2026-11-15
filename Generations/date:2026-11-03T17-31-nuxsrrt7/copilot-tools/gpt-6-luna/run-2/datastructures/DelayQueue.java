@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final long MAX_INTERVAL_NANOS = Long.MAX_VALUE / 2;

    private final Object lock = new Object();
    private Object[] heap = new Object[INITIAL_CAPACITY];
    private int size;

    private static final class Entry<T> {
        final T value;
        final long deadline;

        Entry(T value, long deadline) {
            this.value = value;
            this.deadline = deadline;
        }
    }

    /**
     * Inserts an element that becomes available after {@code delayNanos}.
     * Delays must be between zero and Long.MAX_VALUE / 2 nanoseconds.
     */
    public boolean offer(E element, long delayNanos) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        if (delayNanos < 0 || delayNanos > MAX_INTERVAL_NANOS) {
            throw new IllegalArgumentException("delayNanos is out of range");
        }

        synchronized (lock) {
            ensureCapacity();
            Entry<E> entry = new Entry<E>(element, System.nanoTime() + delayNanos);
            heap[size] = entry;
            siftUp(size++);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Returns and removes the earliest element if it has expired; otherwise
     * returns null.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0 || remainingNanos(entryAt(0).deadline) > 0) {
                return null;
            }
            E value = removeAt(0);
            lock.notifyAll();
            return value;
        }
    }

    /**
     * Waits until an element expires, then removes and returns it.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            for (;;) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                long remaining = remainingNanos(entryAt(0).deadline);
                if (remaining <= 0) {
                    E value = removeAt(0);
                    lock.notifyAll();
                    return value;
                }
                waitNanos(remaining);
            }
        }
    }

    /**
     * Waits up to {@code timeoutNanos} for an expired element.
     * A nonpositive timeout returns immediately.
     */
    public E poll(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos > MAX_INTERVAL_NANOS) {
            throw new IllegalArgumentException("timeoutNanos is out of range");
        }
        if (timeoutNanos <= 0) {
            return poll();
        }

        synchronized (lock) {
            long start = System.nanoTime();
            long remainingTimeout = timeoutNanos;
            for (;;) {
                if (size != 0) {
                    long remainingDelay = remainingNanos(entryAt(0).deadline);
                    if (remainingDelay <= 0) {
                        E value = removeAt(0);
                        lock.notifyAll();
                        return value;
                    }
                    if (remainingDelay < remainingTimeout) {
                        waitNanos(remainingDelay);
                    } else {
                        waitNanos(remainingTimeout);
                    }
                } else {
                    waitNanos(remainingTimeout);
                }

                long elapsed = System.nanoTime() - start;
                if (elapsed >= timeoutNanos) {
                    return null;
                }
                remainingTimeout = timeoutNanos - elapsed;
            }
        }
    }

    /**
     * Returns the earliest element, whether or not it has expired.
     */
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : entryAt(0).value;
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

    /**
     * Removes the first element equal to {@code element}, if present.
     */
    public boolean remove(E element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (element.equals(entryAt(i).value)) {
                    removeAt(i);
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                for (int i = 0; i < size; i++) {
                    heap[i] = null;
                }
                size = 0;
                lock.notifyAll();
            }
        }
    }

    private void waitNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        lock.wait(millis, extraNanos);
    }

    private long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }
        if (heap.length == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("DelayQueue capacity exceeded");
        }

        int newCapacity = heap.length > Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE
                : heap.length * 2;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }

    private Entry<E> entryAt(int index) {
        @SuppressWarnings("unchecked")
        Entry<E> entry = (Entry<E>) heap[index];
        return entry;
    }

    private static boolean comesBefore(long firstDeadline, long secondDeadline) {
        return firstDeadline - secondDeadline < 0;
    }

    private void siftUp(int index) {
        Entry<E> entry = entryAt(index);
        while (index > 0) {
            int parent = (index - 1) / 2;
            Entry<E> parentEntry = entryAt(parent);
            if (!comesBefore(entry.deadline, parentEntry.deadline)) {
                break;
            }
            heap[index] = parentEntry;
            index = parent;
        }
        heap[index] = entry;
    }

    private void siftDown(int index) {
        Entry<E> entry = entryAt(index);
        int half = size / 2;
        while (index < half) {
            int child = index * 2 + 1;
            int right = child + 1;
            if (right < size
                    && comesBefore(entryAt(right).deadline, entryAt(child).deadline)) {
                child = right;
            }
            Entry<E> childEntry = entryAt(child);
            if (!comesBefore(childEntry.deadline, entry.deadline)) {
                break;
            }
            heap[index] = childEntry;
            index = child;
        }
        heap[index] = entry;
    }

    private E removeAt(int index) {
        Entry<E> removed = entryAt(index);
        int lastIndex = --size;
        Entry<E> replacement = entryAt(lastIndex);
        heap[lastIndex] = null;

        if (index != lastIndex) {
            heap[index] = replacement;
            if (index > 0
                    && comesBefore(replacement.deadline, entryAt((index - 1) / 2).deadline)) {
                siftUp(index);
            } else {
                siftDown(index);
            }
        }
        return removed.value;
    }
}
