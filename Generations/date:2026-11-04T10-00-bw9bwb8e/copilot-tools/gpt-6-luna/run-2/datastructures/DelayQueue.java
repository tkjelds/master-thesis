@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4L;

    private static final class Entry<T> {
        final T value;
        final long deadlineNanos;

        Entry(T value, long deadlineNanos) {
            this.value = value;
            this.deadlineNanos = deadlineNanos;
        }
    }

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

        long delayNanos;
        if (delayMillis <= 0) {
            delayNanos = 0;
        } else if (delayMillis > MAX_DELAY_NANOS / NANOS_PER_MILLI) {
            delayNanos = MAX_DELAY_NANOS;
        } else {
            delayNanos = delayMillis * NANOS_PER_MILLI;
        }

        synchronized (this) {
            boolean becomesHead =
                    size == 0 || isEarlier(System.nanoTime() + delayNanos, heap[0].deadlineNanos);
            insert(new Entry<E>(value, System.nanoTime() + delayNanos));
            if (becomesHead) {
                notifyAll();
            }
            return true;
        }
    }

    public void put(E value, long delayMillis) {
        offer(value, delayMillis);
    }

    public E take() throws InterruptedException {
        synchronized (this) {
            while (true) {
                if (size == 0) {
                    wait();
                    continue;
                }

                long remaining = heap[0].deadlineNanos - System.nanoTime();
                if (remaining > 0) {
                    long millis = remaining / NANOS_PER_MILLI;
                    int nanos = (int) (remaining % NANOS_PER_MILLI);
                    wait(millis, nanos);
                    continue;
                }

                E value = removeHead().value;
                notifyAll();
                return value;
            }
        }
    }

    public E poll() {
        synchronized (this) {
            if (size == 0 || heap[0].deadlineNanos - System.nanoTime() > 0) {
                return null;
            }
            E value = removeHead().value;
            notifyAll();
            return value;
        }
    }

    public E peek() {
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

    private void insert(Entry<E> entry) {
        if (size == heap.length) {
            @SuppressWarnings("unchecked")
            Entry<E>[] grown = (Entry<E>[]) new Entry[heap.length * 2];
            System.arraycopy(heap, 0, grown, 0, size);
            heap = grown;
        }

        int index = size++;
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (!isEarlier(entry.deadlineNanos, heap[parent].deadlineNanos)) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = entry;
    }

    private Entry<E> removeHead() {
        Entry<E> result = heap[0];
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
                int earlierChild = left;
                if (right < size
                        && isEarlier(heap[right].deadlineNanos, heap[left].deadlineNanos)) {
                    earlierChild = right;
                }
                if (!isEarlier(heap[earlierChild].deadlineNanos, last.deadlineNanos)) {
                    break;
                }
                heap[index] = heap[earlierChild];
                index = earlierChild;
            }
            heap[index] = last;
        }

        return result;
    }

    private static boolean isEarlier(long first, long second) {
        return first - second < 0;
    }
}
