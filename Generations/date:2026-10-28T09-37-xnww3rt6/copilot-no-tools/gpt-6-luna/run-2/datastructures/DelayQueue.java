@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;

    private final Object lock = new Object();
    private Object[] heap = new Object[INITIAL_CAPACITY];
    private int size;

    private static final class Entry<T> {
        final T value;
        final long deadlineNanos;

        Entry(T value, long deadlineNanos) {
            this.value = value;
            this.deadlineNanos = deadlineNanos;
        }
    }

    public boolean offer(E element, long delayNanos) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        if (delayNanos < 0) {
            throw new IllegalArgumentException("delayNanos must not be negative");
        }

        synchronized (lock) {
            ensureCapacity();
            Entry<E> entry = new Entry<>(element, System.nanoTime() + delayNanos);
            int index = size++;
            heap[index] = entry;

            while (index > 0) {
                int parent = (index - 1) >>> 1;
                Entry<E> parentEntry = entryAt(parent);
                if (!comesBefore(entry, parentEntry)) {
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

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                Entry<E> first = entryAt(0);
                long remainingNanos = first.deadlineNanos - System.nanoTime();
                if (remainingNanos > 0) {
                    long millis = remainingNanos / 1_000_000L;
                    int nanos = (int) (remainingNanos % 1_000_000L);
                    lock.wait(millis, nanos);
                    continue;
                }

                E result = removeHead();
                lock.notifyAll();
                return result;
            }
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0 || entryAt(0).deadlineNanos - System.nanoTime() > 0) {
                return null;
            }

            E result = removeHead();
            lock.notifyAll();
            return result;
        }
    }

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

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int newCapacity = heap.length <= Integer.MAX_VALUE / 2
                ? heap.length * 2
                : Integer.MAX_VALUE;
        if (newCapacity == heap.length) {
            throw new OutOfMemoryError("Queue capacity exceeded");
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }

    @SuppressWarnings("unchecked")
    private Entry<E> entryAt(int index) {
        return (Entry<E>) heap[index];
    }

    private boolean comesBefore(Entry<E> first, Entry<E> second) {
        return first.deadlineNanos - second.deadlineNanos < 0;
    }

    private E removeHead() {
        Entry<E> first = entryAt(0);
        int lastIndex = --size;
        Object last = heap[lastIndex];
        heap[lastIndex] = null;

        if (lastIndex > 0) {
            int index = 0;
            while (true) {
                int left = (index << 1) + 1;
                if (left >= size) {
                    break;
                }

                int right = left + 1;
                int child = left;
                if (right < size && comesBefore(entryAt(right), entryAt(left))) {
                    child = right;
                }

                Entry<E> childEntry = entryAt(child);
                if (!comesBefore(childEntry, (Entry<E>) last)) {
                    break;
                }
                heap[index] = childEntry;
                index = child;
            }
            heap[index] = last;
        }

        return first.value;
    }
}
