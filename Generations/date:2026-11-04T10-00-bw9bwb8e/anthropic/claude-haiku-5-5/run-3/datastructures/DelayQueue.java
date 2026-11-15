@DelayQueue.ThreadSafe
public final class DelayQueue<T> {
    @interface ThreadSafe {}


    private static final int INITIAL_CAPACITY = 16;
    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4L;

    private static final class Entry {
        final Object item;
        final long triggerNanos;
        final long seq;

        Entry(Object item, long triggerNanos, long seq) {
            this.item = item;
            this.triggerNanos = triggerNanos;
            this.seq = seq;
        }
    }

    private final Object lock = new Object();
    private Object[] heap = new Object[INITIAL_CAPACITY];
    private int size;
    private long nextSeq;

    /**
     * Inserts an element that becomes available after the given delay.
     * The queue is unbounded, so this operation never blocks.
     * Negative delays are treated as zero; delays are clamped to a safe maximum.
     *
     * @throws NullPointerException if item is null
     */
    public boolean offer(T item, long delayNanos) {
        if (item == null) {
            throw new NullPointerException("item must not be null");
        }
        long delay = delayNanos < 0L ? 0L : Math.min(delayNanos, MAX_DELAY_NANOS);
        synchronized (lock) {
            long trigger = System.nanoTime() + delay;
            if (size == heap.length) {
                Object[] bigger = new Object[heap.length * 2];
                for (int i = 0; i < size; i++) {
                    bigger[i] = heap[i];
                }
                heap = bigger;
            }
            heap[size] = new Entry(item, trigger, nextSeq++);
            siftUp(size);
            size++;
            // Head may have changed and waiting takers must re-evaluate.
            lock.notifyAll();
        }
        return true;
    }

    /**
     * Blocks until the head element's delay has expired, then removes and returns it.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }
                long remaining = ((Entry) heap[0]).triggerNanos - System.nanoTime();
                if (remaining <= 0L) {
                    return removeHead();
                }
                waitNanos(remaining);
            }
        }
    }

    /**
     * Non-blocking: removes and returns the head element if its delay has expired,
     * otherwise returns null.
     */
    public T poll() {
        synchronized (lock) {
            if (size > 0 && ((Entry) heap[0]).triggerNanos - System.nanoTime() <= 0L) {
                return removeHead();
            }
            return null;
        }
    }

    /**
     * Waits at most timeoutNanos for an expired element to become available.
     * Returns null if the timeout elapses first.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public T poll(long timeoutNanos) throws InterruptedException {
        long start = System.nanoTime();
        synchronized (lock) {
            while (true) {
                long now = System.nanoTime();
                long left = timeoutNanos - (now - start);
                boolean hasHead = size > 0;
                long waitNanos = left;
                if (hasHead) {
                    long rem = ((Entry) heap[0]).triggerNanos - now;
                    if (rem <= 0L) {
                        return removeHead();
                    }
                    if (rem < waitNanos) {
                        waitNanos = rem;
                    }
                }
                if (left <= 0L) {
                    return null;
                }
                waitNanos(waitNanos);
            }
        }
    }

    /** Returns the number of elements currently held, expired or not. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns true if the queue holds no elements. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    // ---- Must be called while holding lock ----

    @SuppressWarnings("unchecked")
    private T removeHead() {
        Entry top = (Entry) heap[0];
        size--;
        heap[0] = heap[size];
        heap[size] = null;
        if (size > 0) {
            siftDown(0);
        }
        return (T) top.item;
    }

    private void waitNanos(long nanos) throws InterruptedException {
        // nanos must be > 0; Object.wait(0, 0) would wait forever.
        long ms = nanos / 1_000_000L;
        int ns = (int) (nanos % 1_000_000L);
        lock.wait(ms, ns);
    }

    // Ordering by trigger time (wrap-safe difference), then insertion sequence (FIFO tie-break).
    private static boolean less(Entry a, Entry b) {
        long diff = a.triggerNanos - b.triggerNanos;
        if (diff != 0L) {
            return diff < 0L;
        }
        return a.seq < b.seq;
    }

    private void siftUp(int k) {
        Entry x = (Entry) heap[k];
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Entry p = (Entry) heap[parent];
            if (!less(x, p)) {
                break;
            }
            heap[k] = p;
            k = parent;
        }
        heap[k] = x;
    }

    private void siftDown(int k) {
        Entry x = (Entry) heap[k];
        int half = size >>> 1;
        while (k < half) {
            int child = (k << 1) + 1;
            Entry c = (Entry) heap[child];
            int right = child + 1;
            if (right < size && less((Entry) heap[right], c)) {
                child = right;
                c = (Entry) heap[child];
            }
            if (!less(c, x)) {
                break;
            }
            heap[k] = c;
            k = child;
        }
        heap[k] = x;
    }
}
