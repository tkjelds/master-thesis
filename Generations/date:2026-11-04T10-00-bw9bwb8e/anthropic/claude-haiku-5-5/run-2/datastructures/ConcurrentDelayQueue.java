@ConcurrentDelayQueue.ThreadSafe
public final class ConcurrentDelayQueue<E extends ConcurrentDelayQueue.Delayed> {
    @interface ThreadSafe {}


    /**
     * Elements stored in this queue must report an absolute deadline measured
     * against System.nanoTime(). An element is available once
     * System.nanoTime() - getDeadlineNanos() >= 0.
     */
    public interface Delayed {
        long getDeadlineNanos();
    }

    private static final class Entry {
        final Delayed item;
        final long seq;

        Entry(Delayed item, long seq) {
            this.item = item;
            this.seq = seq;
        }
    }

    private static final int INITIAL_CAPACITY = 16;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    // All mutable state below is guarded by 'lock'.
    private final Object lock = new Object();
    private Entry[] heap = new Entry[INITIAL_CAPACITY];
    private int size;
    private long nextSeq;

    /**
     * Policy: the queue is unbounded, so put never blocks and always succeeds.
     * Null elements are rejected with NullPointerException.
     */
    public void put(E e) {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == heap.length) {
                Entry[] bigger = new Entry[heap.length << 1];
                System.arraycopy(heap, 0, bigger, 0, size);
                heap = bigger;
            }
            Entry entry = new Entry(e, nextSeq++);
            siftUp(size, entry);
            size++;
            // Wake takers/pollers so they can re-evaluate the head.
            lock.notifyAll();
        }
    }

    /** Equivalent to put; always returns true because the queue is unbounded. */
    public boolean offer(E e) {
        put(e);
        return true;
    }

    /**
     * Blocks until the head element's delay has expired, then removes and returns it.
     * Waits on the monitor with guarded while-loops; no busy-waiting.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                while (size == 0) {
                    lock.wait();
                }
                long delay = heap[0].item.getDeadlineNanos() - System.nanoTime();
                if (delay <= 0) {
                    return removeHeadLocked();
                }
                lock.wait(ceilMillis(delay));
            }
        }
    }

    /**
     * Non-blocking: returns the head element if its delay has expired, otherwise null.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            long delay = heap[0].item.getDeadlineNanos() - System.nanoTime();
            if (delay > 0) {
                return null;
            }
            return removeHeadLocked();
        }
    }

    /**
     * Waits up to timeoutNanos for an expired element. Returns null on timeout.
     * Negative timeouts are treated as zero.
     */
    public E pollNanos(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos < 0) {
            timeoutNanos = 0;
        }
        final long end = System.nanoTime() + timeoutNanos;
        synchronized (lock) {
            while (true) {
                long now = System.nanoTime();
                if (size > 0) {
                    long headDelay = heap[0].item.getDeadlineNanos() - now;
                    if (headDelay <= 0) {
                        return removeHeadLocked();
                    }
                }
                long remaining = end - now;
                if (remaining <= 0) {
                    return null;
                }
                long waitNanos = remaining;
                if (size > 0) {
                    long headDelay = heap[0].item.getDeadlineNanos() - now;
                    if (headDelay < waitNanos) {
                        waitNanos = headDelay;
                    }
                }
                lock.wait(ceilMillis(waitNanos));
            }
        }
    }

    /**
     * Returns the head element (expired or not) without removing it, or null if empty.
     */
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return castItem(heap[0].item);
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
        }
    }

    // ---- Private helpers; callers must hold 'lock'. ----

    @SuppressWarnings("unchecked")
    private E removeHeadLocked() {
        Entry result = heap[0];
        int n = --size;
        Entry last = heap[n];
        heap[n] = null;
        if (n > 0) {
            siftDown(0, last, n);
        }
        return castItem(result.item);
    }

    @SuppressWarnings("unchecked")
    private E castItem(Delayed d) {
        return (E) d;
    }

    /** Orders by deadline (wrap-safe subtraction), then by insertion sequence for FIFO ties. */
    private static boolean less(Entry a, Entry b) {
        long d = a.item.getDeadlineNanos() - b.item.getDeadlineNanos();
        if (d != 0) {
            return d < 0;
        }
        return a.seq < b.seq;
    }

    private void siftUp(int k, Entry x) {
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Entry p = heap[parent];
            if (!less(x, p)) {
                break;
            }
            heap[k] = p;
            k = parent;
        }
        heap[k] = x;
    }

    private void siftDown(int k, Entry x, int n) {
        int half = n >>> 1;
        while (k < half) {
            int child = (k << 1) + 1;
            int right = child + 1;
            if (right < n && less(heap[right], heap[child])) {
                child = right;
            }
            if (!less(heap[child], x)) {
                break;
            }
            heap[k] = heap[child];
            k = child;
        }
        heap[k] = x;
    }

    /** Rounds a positive nanosecond duration up to whole milliseconds (minimum 1). */
    private static long ceilMillis(long nanos) {
        long ms = nanos / NANOS_PER_MILLI;
        if (nanos % NANOS_PER_MILLI != 0) {
            ms++;
        }
        return ms < 1 ? 1 : ms;
    }
}
