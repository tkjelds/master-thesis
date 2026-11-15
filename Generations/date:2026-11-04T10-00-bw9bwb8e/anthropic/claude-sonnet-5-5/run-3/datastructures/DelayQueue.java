@DelayQueue.ThreadSafe
public class DelayQueue<E> {
    @interface ThreadSafe {}


    private static final class Node {
        final Object item;
        final long expiry;   // System.nanoTime() based
        final long seq;      // FIFO tie-break among equal expiries

        Node(Object item, long expiry, long seq) {
            this.item = item;
            this.expiry = expiry;
            this.seq = seq;
        }
    }

    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4;

    private final Object lock = new Object();
    private Node[] heap = new Node[16];
    private int size = 0;
    private long sequence = 0;

    public DelayQueue() {
    }

    // ---------- public API ----------

    /** Inserts an element that becomes available after the given delay (in milliseconds). */
    public void put(E item, long delayMillis) {
        if (delayMillis > Long.MAX_VALUE / 1_000_000L) {
            offerNanos(item, MAX_DELAY_NANOS);
        } else {
            offerNanos(item, delayMillis * 1_000_000L);
        }
    }

    /** Inserts an element that becomes available after the given delay (in nanoseconds). */
    public void offerNanos(E item, long delayNanos) {
        if (item == null) {
            throw new NullPointerException();
        }
        if (delayNanos < 0) {
            delayNanos = 0;
        }
        if (delayNanos > MAX_DELAY_NANOS) {
            delayNanos = MAX_DELAY_NANOS;
        }
        synchronized (lock) {
            long expiry = System.nanoTime() + delayNanos;
            if (size == heap.length) {
                Node[] bigger = new Node[heap.length * 2];
                for (int i = 0; i < size; i++) {
                    bigger[i] = heap[i];
                }
                heap = bigger;
            }
            siftUp(size, new Node(item, expiry, sequence++));
            size++;
            lock.notifyAll();
        }
    }

    /** Blocks until the head element's delay has expired, then removes and returns it. */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                } else {
                    long remaining = heap[0].expiry - System.nanoTime();
                    if (remaining <= 0) {
                        return removeHead();
                    }
                    waitNanos(remaining);
                }
            }
        }
    }

    /** Returns and removes an expired head element, or null if none is currently expired. */
    public E poll() {
        synchronized (lock) {
            if (size == 0 || heap[0].expiry - System.nanoTime() > 0) {
                return null;
            }
            return removeHead();
        }
    }

    /** Waits up to the given timeout for an expired element; returns null on timeout. */
    public E poll(long timeoutMillis) throws InterruptedException {
        long timeoutNanos;
        if (timeoutMillis <= 0) {
            timeoutNanos = 0;
        } else if (timeoutMillis > Long.MAX_VALUE / 1_000_000L) {
            timeoutNanos = MAX_DELAY_NANOS;
        } else {
            timeoutNanos = Math.min(timeoutMillis * 1_000_000L, MAX_DELAY_NANOS);
        }
        long start = System.nanoTime();
        synchronized (lock) {
            while (true) {
                long now = System.nanoTime();
                long left = timeoutNanos - (now - start);
                if (size > 0) {
                    long headRemaining = heap[0].expiry - now;
                    if (headRemaining <= 0) {
                        return removeHead();
                    }
                    if (left <= 0) {
                        return null;
                    }
                    waitNanos(Math.min(left, headRemaining));
                } else {
                    if (left <= 0) {
                        return null;
                    }
                    waitNanos(left);
                }
            }
        }
    }

    /** Number of elements in the queue, expired or not. */
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

    /** Removes all elements. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    // ---------- internals (must be called holding lock) ----------

    private void waitNanos(long nanos) throws InterruptedException {
        // nanos > 0 guaranteed by callers
        long ms = nanos / 1_000_000L;
        int ns = (int) (nanos % 1_000_000L);
        lock.wait(ms, ns);
    }

    @SuppressWarnings("unchecked")
    private E removeHead() {
        Node result = heap[0];
        int n = --size;
        Node last = heap[n];
        heap[n] = null;
        if (n > 0) {
            siftDown(0, last, n);
        }
        // Remaining elements may still be available to other waiters.
        lock.notifyAll();
        return (E) result.item;
    }

    private static boolean less(Node a, Node b) {
        long diff = a.expiry - b.expiry;
        if (diff != 0) {
            return diff < 0;
        }
        return a.seq < b.seq;
    }

    private void siftUp(int k, Node x) {
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Node p = heap[parent];
            if (!less(x, p)) {
                break;
            }
            heap[k] = p;
            k = parent;
        }
        heap[k] = x;
    }

    private void siftDown(int k, Node x, int n) {
        int half = n >>> 1;
        while (k < half) {
            int child = 2 * k + 1;
            Node c = heap[child];
            int right = child + 1;
            if (right < n && less(heap[right], c)) {
                child = right;
                c = heap[child];
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
