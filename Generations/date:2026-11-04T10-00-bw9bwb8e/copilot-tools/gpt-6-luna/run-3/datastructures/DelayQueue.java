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

    private final Object monitor = new Object();

    @SuppressWarnings("unchecked")
    private Entry<E>[] heap = (Entry<E>[]) new Entry<?>[16];

    private int size;

    /** Inserts an element that becomes available after delayNanos. Negative delays expire immediately. */
    public boolean offer(E element, long delayNanos) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (monitor) {
            ensureCapacity();
            long delay = delayNanos > 0 ? delayNanos : 0;
            Entry<E> entry = new Entry<E>(element, System.nanoTime() + delay);
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
            monitor.notifyAll();
            return true;
        }
    }

    /** Inserts an element into this unbounded queue. */
    public void put(E element, long delayNanos) {
        offer(element, delayNanos);
    }

    /** Returns and removes an expired element, or null if none is available. */
    public E poll() {
        synchronized (monitor) {
            if (size == 0 || remainingNanos(heap[0].deadline) > 0) {
                return null;
            }
            return removeHead();
        }
    }

    /** Returns and removes the next expired element, waiting until one is available. */
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                if (size == 0) {
                    monitor.wait();
                    continue;
                }

                long remaining = remainingNanos(heap[0].deadline);
                if (remaining <= 0) {
                    return removeHead();
                }

                waitForNanos(remaining);
            }
        }
    }

    /**
     * Returns and removes an expired element, waiting up to timeoutNanos.
     * A nonpositive timeout performs an immediate poll.
     */
    public E poll(long timeoutNanos) throws InterruptedException {
        synchronized (monitor) {
            long start = System.nanoTime();

            while (true) {
                if (size > 0) {
                    long untilReady = remainingNanos(heap[0].deadline);
                    if (untilReady <= 0) {
                        return removeHead();
                    }
                }

                long elapsed = System.nanoTime() - start;
                long remainingTimeout = timeoutNanos - elapsed;
                if (timeoutNanos <= 0 || remainingTimeout <= 0) {
                    return null;
                }

                long waitNanos = remainingTimeout;
                if (size > 0) {
                    long untilReady = remainingNanos(heap[0].deadline);
                    if (untilReady > 0 && untilReady < waitNanos) {
                        waitNanos = untilReady;
                    }
                }
                waitForNanos(waitNanos);
            }
        }
    }

    /** Returns the earliest-expiring element, whether or not it has expired. */
    public E peek() {
        synchronized (monitor) {
            return size == 0 ? null : heap[0].value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            monitor.notifyAll();
        }
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int newCapacity = heap.length + (heap.length >>> 1);
        if (newCapacity <= heap.length) {
            throw new OutOfMemoryError("Queue capacity exceeded");
        }

        @SuppressWarnings("unchecked")
        Entry<E>[] replacement = (Entry<E>[]) new Entry<?>[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = heap[i];
        }
        heap = replacement;
    }

    private E removeHead() {
        E value = heap[0].value;
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

        monitor.notifyAll();
        return value;
    }

    private static boolean comesBefore(Entry<?> first, Entry<?> second) {
        return first.deadline - second.deadline < 0;
    }

    private static long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private void waitForNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        monitor.wait(millis, extraNanos);
    }
}
