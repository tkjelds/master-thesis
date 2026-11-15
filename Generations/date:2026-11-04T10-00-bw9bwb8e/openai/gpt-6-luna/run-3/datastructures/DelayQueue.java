@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final class Entry<T> {
        final T value;
        final long deadline;

        Entry(T value, long deadline) {
            this.value = value;
            this.deadline = deadline;
        }
    }

    private Entry<E>[] heap;
    private int size;

    @SuppressWarnings("unchecked")
    public DelayQueue() {
        heap = (Entry<E>[]) new Entry[16];
    }

    /**
     * Adds an element that becomes available after delayMillis.
     * Negative or zero delays make the element available immediately.
     * This queue is unbounded, so adding an element never waits for capacity.
     */
    public boolean offer(E element, long delayMillis) {
        if (element == null) {
            throw new IllegalArgumentException("element must not be null");
        }

        long delayNanos = toNanos(delayMillis);
        long deadline = System.nanoTime() + delayNanos;

        synchronized (this) {
            ensureCapacity();
            insert(new Entry<E>(element, deadline));
            notifyAll();
            return true;
        }
    }

    /**
     * Adds an element with a delay expressed in nanoseconds.
     */
    public boolean offerNanos(E element, long delayNanos) {
        if (element == null) {
            throw new IllegalArgumentException("element must not be null");
        }

        if (delayNanos < 0) {
            delayNanos = 0;
        }
        long deadline = System.nanoTime() + delayNanos;

        synchronized (this) {
            ensureCapacity();
            insert(new Entry<E>(element, deadline));
            notifyAll();
            return true;
        }
    }

    /**
     * Adds an element with a delay expressed in milliseconds.
     * This queue is unbounded, so this method never waits for capacity.
     */
    public void put(E element, long delayMillis) {
        offer(element, delayMillis);
    }

    /**
     * Returns and removes the earliest element if its delay has expired;
     * otherwise returns null immediately.
     */
    public E poll() {
        synchronized (this) {
            if (size == 0 || remainingNanos(heap[0].deadline) > 0) {
                return null;
            }
            return removeHead();
        }
    }

    /**
     * Waits until an element's delay expires, then returns and removes it.
     */
    public E take() throws InterruptedException {
        synchronized (this) {
            while (true) {
                if (size == 0) {
                    wait();
                    continue;
                }

                long remaining = remainingNanos(heap[0].deadline);
                if (remaining <= 0) {
                    return removeHead();
                }
                waitNanos(remaining);
            }
        }
    }

    /**
     * Waits up to timeoutMillis for an element to become available.
     * A zero or negative timeout performs an immediate poll.
     */
    public E poll(long timeoutMillis) throws InterruptedException {
        long timeoutNanos = toNanos(timeoutMillis);
        long deadline = System.nanoTime() + timeoutNanos;

        synchronized (this) {
            while (true) {
                if (size > 0) {
                    long delayRemaining = remainingNanos(heap[0].deadline);
                    if (delayRemaining <= 0) {
                        return removeHead();
                    }

                    long timeoutRemaining = remainingNanos(deadline);
                    if (timeoutRemaining <= 0) {
                        return null;
                    }

                    waitNanos(delayRemaining < timeoutRemaining
                            ? delayRemaining : timeoutRemaining);
                } else {
                    long timeoutRemaining = remainingNanos(deadline);
                    if (timeoutRemaining <= 0) {
                        return null;
                    }
                    waitNanos(timeoutRemaining);
                }
            }
        }
    }

    /**
     * Returns the earliest element, whether or not its delay has expired.
     */
    public E peek() {
        synchronized (this) {
            return size == 0 ? null : heap[0].value;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            notifyAll();
        }
    }

    private void insert(Entry<E> entry) {
        int index = size++;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (!comesBefore(entry, heap[parent])) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = entry;
    }

    private E removeHead() {
        Entry<E> result = heap[0];
        Entry<E> last = heap[--size];
        heap[size] = null;

        if (size > 0) {
            int index = 0;
            while (true) {
                int left = (index << 1) + 1;
                if (left >= size) {
                    break;
                }

                int right = left + 1;
                int child = right < size && comesBefore(heap[right], heap[left])
                        ? right : left;

                if (!comesBefore(heap[child], last)) {
                    break;
                }
                heap[index] = heap[child];
                index = child;
            }
            heap[index] = last;
        }

        notifyAll();
        return result.value;
    }

    private boolean comesBefore(Entry<E> first, Entry<E> second) {
        return first.deadline - second.deadline < 0;
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int newCapacity = heap.length < 1024
                ? heap.length * 2
                : heap.length + (heap.length >>> 1);

        if (newCapacity <= heap.length) {
            throw new OutOfMemoryError("DelayQueue capacity exceeded");
        }

        @SuppressWarnings("unchecked")
        Entry<E>[] replacement = (Entry<E>[]) new Entry[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = heap[i];
        }
        heap = replacement;
    }

    private static long toNanos(long millis) {
        if (millis <= 0) {
            return 0;
        }
        if (millis > Long.MAX_VALUE / 1_000_000L) {
            return Long.MAX_VALUE;
        }
        return millis * 1_000_000L;
    }

    private static long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private void waitNanos(long nanos) throws InterruptedException {
        if (nanos <= 0) {
            return;
        }
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        wait(millis, extraNanos);
    }
}
