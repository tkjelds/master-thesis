@DelayQueue.ThreadSafe
public final class DelayQueue<T> {
    @interface ThreadSafe {}

    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 2;

    private static final class Entry {
        final Object value;
        final long deadlineNanos;

        Entry(Object value, long deadlineNanos) {
            this.value = value;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Object monitor = new Object();
    private Entry[] heap = new Entry[16];
    private int size;

    public boolean offer(T value, long delayMillis) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }

        long maxDelayMillis = MAX_DELAY_NANOS / 1_000_000L;
        long delayNanos = delayMillis > maxDelayMillis
                ? MAX_DELAY_NANOS
                : delayMillis * 1_000_000L;
        Entry entry = new Entry(value, System.nanoTime() + delayNanos);

        synchronized (monitor) {
            ensureCapacity();
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) / 2;
                if (heap[parent].deadlineNanos <= entry.deadlineNanos) {
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

    public T poll() {
        synchronized (monitor) {
            if (size == 0 || heap[0].deadlineNanos - System.nanoTime() > 0) {
                return null;
            }

            T value = valueOf(removeRoot());
            monitor.notifyAll();
            return value;
        }
    }

    public T take() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                if (size == 0) {
                    monitor.wait();
                    continue;
                }

                long remainingNanos = heap[0].deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    T value = valueOf(removeRoot());
                    monitor.notifyAll();
                    return value;
                }

                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                monitor.wait(millis, nanos);
            }
        }
    }

    public T peek() {
        synchronized (monitor) {
            return size == 0 ? null : valueOf(heap[0]);
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

        int newCapacity = heap.length <= Integer.MAX_VALUE / 2
                ? heap.length * 2
                : Integer.MAX_VALUE;
        if (newCapacity == heap.length) {
            throw new OutOfMemoryError("DelayQueue capacity exceeded");
        }

        Entry[] replacement = new Entry[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = heap[i];
        }
        heap = replacement;
    }

    private Entry removeRoot() {
        Entry root = heap[0];
        Entry last = heap[--size];
        heap[size] = null;

        if (size > 0) {
            int index = 0;
            while (true) {
                int left = index * 2 + 1;
                if (left >= size) {
                    break;
                }

                int right = left + 1;
                int child = right < size
                        && heap[right].deadlineNanos < heap[left].deadlineNanos
                        ? right
                        : left;

                if (heap[child].deadlineNanos >= last.deadlineNanos) {
                    break;
                }

                heap[index] = heap[child];
                index = child;
            }
            heap[index] = last;
        }

        return root;
    }

    @SuppressWarnings("unchecked")
    private T valueOf(Entry entry) {
        return (T) entry.value;
    }
}
