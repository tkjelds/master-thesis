@DelayQueue.ThreadSafe
public class DelayQueue<E> {
    @interface ThreadSafe {}


    private static final long MAX_DELAY = Long.MAX_VALUE / 4;

    private static final class Entry {
        final Object item;
        final long deadline;
        final long seq;

        Entry(Object item, long deadline, long seq) {
            this.item = item;
            this.deadline = deadline;
            this.seq = seq;
        }
    }

    private final Object lock = new Object();
    private Entry[] heap = new Entry[16];
    private int size = 0;
    private long seqCounter = 0;

    public DelayQueue() {
    }

    // Returns true if a precedes b.
    private static boolean before(Entry a, Entry b) {
        long diff = a.deadline - b.deadline;
        if (diff != 0) {
            return diff < 0;
        }
        return a.seq < b.seq;
    }

    private void siftUp(int k) {
        Entry e = heap[k];
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            if (!before(e, heap[parent])) {
                break;
            }
            heap[k] = heap[parent];
            k = parent;
        }
        heap[k] = e;
    }

    private void siftDown(int k) {
        Entry e = heap[k];
        int half = size >>> 1;
        while (k < half) {
            int child = 2 * k + 1;
            int right = child + 1;
            if (right < size && before(heap[right], heap[child])) {
                child = right;
            }
            if (!before(heap[child], e)) {
                break;
            }
            heap[k] = heap[child];
            k = child;
        }
        heap[k] = e;
    }

    // Must hold lock.
    private Entry removeHead() {
        Entry result = heap[0];
        size--;
        Entry last = heap[size];
        heap[size] = null;
        if (size > 0) {
            heap[0] = last;
            siftDown(0);
        }
        return result;
    }

    /**
     * Inserts an element that becomes available after delayNanos nanoseconds.
     * Never blocks (unbounded queue).
     */
    public void put(E item, long delayNanos) {
        if (item == null) {
            throw new NullPointerException();
        }
        if (delayNanos > MAX_DELAY) {
            delayNanos = MAX_DELAY;
        }
        if (delayNanos < 0) {
            delayNanos = 0;
        }
        synchronized (lock) {
            long deadline = System.nanoTime() + delayNanos;
            if (size == heap.length) {
                Entry[] newHeap = new Entry[heap.length * 2];
                for (int i = 0; i < size; i++) {
                    newHeap[i] = heap[i];
                }
                heap = newHeap;
            }
            heap[size] = new Entry(item, deadline, seqCounter++);
            siftUp(size);
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Retrieves and removes the head, blocking until an element's delay has expired.
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                } else {
                    long remaining = heap[0].deadline - System.nanoTime();
                    if (remaining <= 0) {
                        Entry e = removeHead();
                        if (size > 0) {
                            lock.notifyAll();
                        }
                        return (E) e.item;
                    }
                    lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
                }
            }
        }
    }

    /**
     * Retrieves and removes the head if its delay has expired; otherwise returns null.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            if (heap[0].deadline - System.nanoTime() > 0) {
                return null;
            }
            Entry e = removeHead();
            return (E) e.item;
        }
    }

    /**
     * Retrieves and removes the head, waiting up to timeoutNanos for an expired element.
     * Returns null if the timeout elapses first.
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos > MAX_DELAY) {
            timeoutNanos = MAX_DELAY;
        }
        synchronized (lock) {
            long limit = System.nanoTime() + (timeoutNanos < 0 ? 0 : timeoutNanos);
            while (true) {
                long now = System.nanoTime();
                long timeLeft = limit - now;
                if (size > 0) {
                    long remaining = heap[0].deadline - now;
                    if (remaining <= 0) {
                        Entry e = removeHead();
                        if (size > 0) {
                            lock.notifyAll();
                        }
                        return (E) e.item;
                    }
                    if (timeLeft <= 0) {
                        return null;
                    }
                    long w = remaining < timeLeft ? remaining : timeLeft;
                    lock.wait(w / 1000000L, (int) (w % 1000000L));
                } else {
                    if (timeLeft <= 0) {
                        return null;
                    }
                    lock.wait(timeLeft / 1000000L, (int) (timeLeft % 1000000L));
                }
            }
        }
    }

    /**
     * Returns the head element (expired or not) without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : (E) heap[0].item;
        }
    }

    /** Number of elements, including those not yet expired. */
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
}
