@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final class Entry<T> {
        final T value;
        final long deadlineNanos;

        Entry(T value, long deadlineNanos) {
            this.value = value;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Object lock = new Object();
    private Entry<E>[] heap;
    private int size;

    @SuppressWarnings("unchecked")
    public DelayQueue() {
        heap = (Entry<E>[]) new Entry[16];
    }

    public boolean offer(E value, long delayMillis) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must be non-negative");
        }

        long delayNanos = delayMillis > Long.MAX_VALUE / NANOS_PER_MILLI
                ? Long.MAX_VALUE
                : delayMillis * NANOS_PER_MILLI;
        long deadline = System.nanoTime() + delayNanos;

        synchronized (lock) {
            ensureCapacity();
            boolean becomesHead = size == 0
                    || deadline - heap[0].deadlineNanos < 0;
            heap[size] = new Entry<E>(value, deadline);
            siftUp(size++);
            if (becomesHead) {
                lock.notifyAll();
            }
            return true;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0 || heap[0].deadlineNanos - System.nanoTime() > 0) {
                return null;
            }
            E value = removeHead();
            lock.notifyAll();
            return value;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                long remainingNanos = heap[0].deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    E value = removeHead();
                    lock.notifyAll();
                    return value;
                }

                long waitMillis = remainingNanos / NANOS_PER_MILLI;
                int waitNanos = (int) (remainingNanos % NANOS_PER_MILLI);
                lock.wait(waitMillis, waitNanos);
            }
        }
    }

    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : heap[0].value;
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

    private void ensureCapacity() {
        if (size == heap.length) {
            @SuppressWarnings("unchecked")
            Entry<E>[] expanded = (Entry<E>[]) new Entry[heap.length * 2];
            for (int i = 0; i < size; i++) {
                expanded[i] = heap[i];
            }
            heap = expanded;
        }
    }

    private void siftUp(int index) {
        Entry<E> entry = heap[index];
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (entry.deadlineNanos - heap[parent].deadlineNanos >= 0) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = entry;
    }

    private E removeHead() {
        E value = heap[0].value;
        Entry<E> last = heap[--size];
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
                        && heap[right].deadlineNanos - heap[left].deadlineNanos < 0
                        ? right
                        : left;
                if (last.deadlineNanos - heap[child].deadlineNanos <= 0) {
                    break;
                }
                heap[index] = heap[child];
                index = child;
            }
            heap[index] = last;
        }
        return value;
    }
}
