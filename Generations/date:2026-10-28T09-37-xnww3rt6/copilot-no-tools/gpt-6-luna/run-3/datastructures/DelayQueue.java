@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final class Entry<T> {
        final T value;
        final long deadline;

        Entry(T value, long deadline) {
            this.value = value;
            this.deadline = deadline;
        }
    }

    private final Object lock = new Object();
    private final Entry<E>[] heap;
    private int size;

    @SuppressWarnings("unchecked")
    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        heap = (Entry<E>[]) new Entry<?>[capacity];
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

    public boolean offer(E value, long delayNanos) {
        checkArguments(value, delayNanos);
        synchronized (lock) {
            if (size == heap.length) {
                return false;
            }
            insert(new Entry<E>(value, System.nanoTime() + delayNanos));
            lock.notifyAll();
            return true;
        }
    }

    public void put(E value, long delayNanos) throws InterruptedException {
        checkArguments(value, delayNanos);
        synchronized (lock) {
            while (size == heap.length) {
                lock.wait();
            }
            insert(new Entry<E>(value, System.nanoTime() + delayNanos));
            lock.notifyAll();
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                long remaining = heap[0].deadline - System.nanoTime();
                if (remaining <= 0) {
                    return removeHead();
                }
                waitNanos(remaining);
            }
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0 || heap[0].deadline - System.nanoTime() > 0) {
                return null;
            }
            return removeHead();
        }
    }

    public E poll(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos < 0) {
            throw new IllegalArgumentException("timeout must not be negative");
        }

        synchronized (lock) {
            long start = System.nanoTime();
            while (true) {
                long elapsed = System.nanoTime() - start;
                long timeoutRemaining = timeoutNanos - elapsed;
                if (timeoutRemaining <= 0) {
                    return null;
                }

                if (size > 0) {
                    long delayRemaining = heap[0].deadline - System.nanoTime();
                    if (delayRemaining <= 0) {
                        return removeHead();
                    }
                    waitNanos(delayRemaining < timeoutRemaining
                            ? delayRemaining
                            : timeoutRemaining);
                } else {
                    waitNanos(timeoutRemaining);
                }
            }
        }
    }

    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : heap[0].value;
        }
    }

    private static void checkArguments(Object value, long delayNanos) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        if (delayNanos < 0) {
            throw new IllegalArgumentException("delay must not be negative");
        }
    }

    private void insert(Entry<E> entry) {
        int index = size++;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (!comesBefore(entry, heap[parent])) {
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
                int left = (index << 1) + 1;
                if (left >= size) {
                    break;
                }
                int right = left + 1;
                int child = right < size && comesBefore(heap[right], heap[left])
                        ? right
                        : left;
                if (!comesBefore(heap[child], last)) {
                    break;
                }
                heap[index] = heap[child];
                index = child;
            }
            heap[index] = last;
        }

        lock.notifyAll();
        return value;
    }

    private static boolean comesBefore(Entry<?> first, Entry<?> second) {
        return first.deadline - second.deadline < 0;
    }

    private void waitNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        lock.wait(millis, extraNanos);
    }
}
