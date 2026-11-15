@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4L;

    private static final class Entry<E> {
        final E value;
        final long deadline;

        Entry(E value, long deadline) {
            this.value = value;
            this.deadline = deadline;
        }
    }

    private Entry<T>[] heap;
    private int size;

    @SuppressWarnings("unchecked")
    public DelayQueue() {
        heap = (Entry<T>[]) new Entry[16];
    }

    public boolean offer(T value, long delayMillis) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        long delayNanos;
        if (delayMillis <= 0) {
            delayNanos = 0L;
        } else if (delayMillis > MAX_DELAY_NANOS / NANOS_PER_MILLI) {
            delayNanos = MAX_DELAY_NANOS;
        } else {
            delayNanos = delayMillis * NANOS_PER_MILLI;
        }

        synchronized (this) {
            ensureCapacity();
            Entry<T> entry = new Entry<T>(value, System.nanoTime() + delayNanos);
            heap[size] = entry;
            siftUp(size++);
            notifyAll();
            return true;
        }
    }

    public T take() throws InterruptedException {
        synchronized (this) {
            while (true) {
                if (size == 0) {
                    wait();
                    continue;
                }

                long remaining = heap[0].deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return removeRoot().value;
                }

                waitNanos(remaining);
            }
        }
    }

    public T poll() {
        synchronized (this) {
            if (size == 0 || heap[0].deadline - System.nanoTime() > 0L) {
                return null;
            }
            return removeRoot().value;
        }
    }

    public T poll(long timeoutMillis) throws InterruptedException {
        long timeoutNanos;
        if (timeoutMillis <= 0) {
            timeoutNanos = 0L;
        } else if (timeoutMillis > MAX_DELAY_NANOS / NANOS_PER_MILLI) {
            timeoutNanos = MAX_DELAY_NANOS;
        } else {
            timeoutNanos = timeoutMillis * NANOS_PER_MILLI;
        }

        synchronized (this) {
            long timeoutDeadline = System.nanoTime() + timeoutNanos;
            while (true) {
                long now = System.nanoTime();

                if (size > 0 && heap[0].deadline - now <= 0L) {
                    return removeRoot().value;
                }

                long timeoutRemaining = timeoutDeadline - now;
                if (timeoutRemaining <= 0L) {
                    return null;
                }

                long waitTime = timeoutRemaining;
                if (size > 0) {
                    long headRemaining = heap[0].deadline - now;
                    if (headRemaining > 0L && headRemaining < waitTime) {
                        waitTime = headRemaining;
                    }
                }
                waitNanos(waitTime);
            }
        }
    }

    public T peek() {
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

    private void waitNanos(long nanos) throws InterruptedException {
        long millis = nanos / NANOS_PER_MILLI;
        int extraNanos = (int) (nanos % NANOS_PER_MILLI);
        wait(millis, extraNanos);
    }

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        @SuppressWarnings("unchecked")
        Entry<T>[] expanded = (Entry<T>[]) new Entry[heap.length * 2];
        for (int i = 0; i < heap.length; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }

    private void siftUp(int index) {
        Entry<T> entry = heap[index];
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            Entry<T> parentEntry = heap[parent];
            if (compare(entry, parentEntry) >= 0) {
                break;
            }
            heap[index] = parentEntry;
            index = parent;
        }
        heap[index] = entry;
    }

    private void siftDown(int index) {
        Entry<T> entry = heap[index];
        int half = size >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;
            if (right < size && compare(heap[right], heap[child]) < 0) {
                child = right;
            }
            if (compare(entry, heap[child]) <= 0) {
                break;
            }
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = entry;
    }

    private Entry<T> removeRoot() {
        Entry<T> result = heap[0];
        Entry<T> last = heap[--size];
        heap[size] = null;
        if (size > 0) {
            heap[0] = last;
            siftDown(0);
        }
        return result;
    }

    private int compare(Entry<T> first, Entry<T> second) {
        long difference = first.deadline - second.deadline;
        return difference < 0L ? -1 : difference > 0L ? 1 : 0;
    }
}
