@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    private static final class Entry<T> {
        final T value;
        final long deadlineHigh;
        final long deadlineLow;

        Entry(T value, long deadlineHigh, long deadlineLow) {
            this.value = value;
            this.deadlineHigh = deadlineHigh;
            this.deadlineLow = deadlineLow;
        }
    }

    private final Object lock = new Object();
    private Object[] heap = new Object[INITIAL_CAPACITY];
    private int size;

    // The logical clock is a two-word unsigned nanosecond counter.
    private long clockHigh;
    private long clockLow;
    private long lastNanoTime = System.nanoTime();

    public DelayQueue() {
    }

    /**
     * Adds an element with a delay measured in nanoseconds.
     * Nonpositive delays make the element immediately available.
     * The queue grows as needed, subject to array and memory limits.
     */
    public boolean offer(E value, long delayNanos) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            ensureCapacity();
            updateClock();

            long delay = delayNanos > 0 ? delayNanos : 0;
            long deadlineLow = clockLow + delay;
            long deadlineHigh = clockHigh;
            if (compareUnsigned(deadlineLow, clockLow) < 0) {
                deadlineHigh++;
            }

            Entry<E> entry = new Entry<E>(value, deadlineHigh, deadlineLow);
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                Entry<E> parentEntry = entryAt(parent);
                if (compareEntries(entry, parentEntry) >= 0) {
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
     * Removes and returns an expired element, waiting if necessary.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                updateClock();
                Entry<E> first = entryAt(0);
                if (compareDeadlineToClock(first) <= 0) {
                    E value = removeFirst();
                    lock.notifyAll();
                    return value;
                }

                long remaining = first.deadlineLow - clockLow;
                long millis = remaining / 1000000L;
                int nanos = (int) (remaining % 1000000L);
                lock.wait(millis, nanos);
            }
        }
    }

    /**
     * Removes and returns an expired element, or null if none is available.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

            updateClock();
            if (compareDeadlineToClock(entryAt(0)) > 0) {
                return null;
            }

            E value = removeFirst();
            lock.notifyAll();
            return value;
        }
    }

    /**
     * Returns the earliest element, whether or not its delay has expired.
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    private void updateClock() {
        long current = System.nanoTime();
        long elapsed = current - lastNanoTime;
        lastNanoTime = current;

        long previous = clockLow;
        clockLow += elapsed;
        if (compareUnsigned(clockLow, previous) < 0) {
            clockHigh++;
        }
    }

    private int compareDeadlineToClock(Entry<E> entry) {
        int high = compareUnsigned(entry.deadlineHigh, clockHigh);
        return high != 0 ? high : compareUnsigned(entry.deadlineLow, clockLow);
    }

    private static int compareEntries(Entry<?> left, Entry<?> right) {
        int high = compareUnsigned(left.deadlineHigh, right.deadlineHigh);
        return high != 0 ? high : compareUnsigned(left.deadlineLow, right.deadlineLow);
    }

    private static int compareUnsigned(long left, long right) {
        long biasedLeft = left ^ Long.MIN_VALUE;
        long biasedRight = right ^ Long.MIN_VALUE;
        return biasedLeft < biasedRight ? -1 : biasedLeft == biasedRight ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private Entry<E> entryAt(int index) {
        return (Entry<E>) heap[index];
    }

    private E removeFirst() {
        Entry<E> first = entryAt(0);
        int lastIndex = --size;
        Entry<E> last = entryAt(lastIndex);
        heap[lastIndex] = null;

        if (lastIndex > 0) {
            int index = 0;
            int half = size >>> 1;
            while (index < half) {
                int child = (index << 1) + 1;
                Entry<E> childEntry = entryAt(child);
                int right = child + 1;
                if (right < size && compareEntries(entryAt(right), childEntry) < 0) {
                    child = right;
                    childEntry = entryAt(right);
                }
                if (compareEntries(last, childEntry) <= 0) {
                    break;
                }
                heap[index] = childEntry;
                index = child;
            }
            heap[index] = last;
        }
        return first.value;
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }
        if (heap.length >= MAX_CAPACITY) {
            throw new IllegalStateException("queue capacity exceeded");
        }

        int newCapacity = heap.length <= MAX_CAPACITY / 2
                ? heap.length * 2 : MAX_CAPACITY;
        Object[] expanded = new Object[newCapacity];
        System.arraycopy(heap, 0, expanded, 0, size);
        heap = expanded;
    }
}
