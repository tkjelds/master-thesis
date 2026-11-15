/**
 * A bounded, thread-safe delay queue. Delays are measured in nanoseconds
 * from successful insertion. Nonpositive delays make an element immediately
 * available. Null elements are not permitted.
 *
 * offer returns false when full; put waits for space. poll returns null when
 * no element is ready; take waits for an element to become ready. peek returns
 * the earliest element even if its delay has not yet expired.
 */
@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final class Entry<T> {
        final T element;
        final long deadlineHigh;
        final long deadlineLow;

        Entry(T element, long deadlineHigh, long deadlineLow) {
            this.element = element;
            this.deadlineHigh = deadlineHigh;
            this.deadlineLow = deadlineLow;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Entry<E>[] heap;
    private int size;

    /*
     * An extended monotonic clock. The two clock fields form an unsigned
     * 128-bit nanosecond count. Sampling assumes less than 2^64 nanoseconds
     * elapse between successive samples.
     */
    private long lastNanoTime;
    private long clockHigh;
    private long clockLow;

    @SuppressWarnings("unchecked")
    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        int initialCapacity = capacity < 16 ? capacity : 16;
        this.heap = (Entry<E>[]) new Entry<?>[initialCapacity];
        this.lastNanoTime = System.nanoTime();
    }

    /** Creates a queue with a maximum capacity of Integer.MAX_VALUE. */
    public DelayQueue() {
        this(0x7fffffff);
    }

    public boolean offer(E element, long delayNanos) {
        requireElement(element);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            insert(element, delayNanos);
            monitor.notifyAll();
            return true;
        }
    }

    public void put(E element, long delayNanos) throws InterruptedException {
        requireElement(element);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            insert(element, delayNanos);
            monitor.notifyAll();
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            updateClock();
            if (remainingNanos(heap[0]) != 0) {
                return null;
            }
            E result = removeHead();
            monitor.notifyAll();
            return result;
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                if (size == 0) {
                    monitor.wait();
                } else {
                    updateClock();
                    long remaining = remainingNanos(heap[0]);
                    if (remaining == 0) {
                        E result = removeHead();
                        monitor.notifyAll();
                        return result;
                    }
                    long millis = remaining / 1000000L;
                    int nanos = (int) (remaining % 1000000L);
                    monitor.wait(millis, nanos);
                }
            }
        }
    }

    public E peek() {
        synchronized (monitor) {
            return size == 0 ? null : heap[0].element;
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
            if (size != 0) {
                for (int i = 0; i < size; i++) {
                    heap[i] = null;
                }
                size = 0;
                monitor.notifyAll();
            }
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
    }

    private static boolean unsignedLess(long a, long b) {
        return (a ^ Long.MIN_VALUE) < (b ^ Long.MIN_VALUE);
    }

    private void updateClock() {
        long now = System.nanoTime();
        long elapsed = now - lastNanoTime;
        lastNanoTime = now;

        long oldLow = clockLow;
        clockLow += elapsed;
        if (unsignedLess(clockLow, oldLow)) {
            clockHigh++;
        }
    }

    private int compare(Entry<E> a, Entry<E> b) {
        if (a.deadlineHigh != b.deadlineHigh) {
            return unsignedLess(a.deadlineHigh, b.deadlineHigh) ? -1 : 1;
        }
        if (a.deadlineLow != b.deadlineLow) {
            return unsignedLess(a.deadlineLow, b.deadlineLow) ? -1 : 1;
        }
        return 0;
    }

    private long remainingNanos(Entry<E> entry) {
        if (unsignedLess(entry.deadlineHigh, clockHigh)
                || (entry.deadlineHigh == clockHigh
                    && !unsignedLess(clockLow, entry.deadlineLow))) {
            return 0;
        }

        long borrow = unsignedLess(entry.deadlineLow, clockLow) ? 1 : 0;
        long highDifference = entry.deadlineHigh - clockHigh - borrow;
        long lowDifference = entry.deadlineLow - clockLow;

        if (highDifference != 0 || lowDifference < 0) {
            return Long.MAX_VALUE;
        }
        return lowDifference;
    }

    private void insert(E element, long delayNanos) {
        ensureCapacity();
        updateClock();

        long delay = delayNanos > 0 ? delayNanos : 0;
        long deadlineLow = clockLow + delay;
        long deadlineHigh = clockHigh;
        if (unsignedLess(deadlineLow, clockLow)) {
            deadlineHigh++;
        }

        Entry<E> entry = new Entry<E>(element, deadlineHigh, deadlineLow);
        int index = size;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (compare(heap[parent], entry) <= 0) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = entry;
        size++;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }
        int newLength = heap.length + (heap.length >>> 1) + 1;
        if (newLength < 0 || newLength > capacity) {
            newLength = capacity;
        }
        Entry<E>[] replacement = (Entry<E>[]) new Entry<?>[newLength];
        for (int i = 0; i < size; i++) {
            replacement[i] = heap[i];
        }
        heap = replacement;
    }

    private E removeHead() {
        E result = heap[0].element;
        int newSize = --size;
        Entry<E> last = heap[newSize];
        heap[newSize] = null;

        if (newSize != 0) {
            int index = 0;
            while (index < newSize / 2) {
                int child = index * 2 + 1;
                int right = child + 1;
                if (right < newSize && compare(heap[right], heap[child]) < 0) {
                    child = right;
                }
                if (compare(last, heap[child]) <= 0) {
                    break;
                }
                heap[index] = heap[child];
                index = child;
            }
            heap[index] = last;
        }
        return result;
    }
}
