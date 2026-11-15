@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MAX_DELAY_MILLIS =
            (Long.MAX_VALUE / 4L) / NANOS_PER_MILLI;

    private static final class Entry<T> {
        final T value;
        final long deadlineNanos;

        Entry(T value, long deadlineNanos) {
            this.value = value;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Object lock = new Object();
    private Object[] entries = new Object[16];
    private int size;

    /**
     * Adds an element with the given delay. Negative delays make it available
     * immediately. Delays longer than the supported range are capped.
     */
    public boolean offer(E element, long delayMillis) {
        if (element == null) {
            throw new IllegalArgumentException("element must not be null");
        }

        synchronized (lock) {
            long delayNanos;
            if (delayMillis <= 0) {
                delayNanos = 0;
            } else if (delayMillis > MAX_DELAY_MILLIS) {
                delayNanos = MAX_DELAY_MILLIS * NANOS_PER_MILLI;
            } else {
                delayNanos = delayMillis * NANOS_PER_MILLI;
            }

            long now = System.nanoTime();
            Entry<E> entry = new Entry<E>(element, now + delayNanos);

            if (size == entries.length) {
                int newLength = entries.length <= Integer.MAX_VALUE / 2
                        ? entries.length * 2 : Integer.MAX_VALUE;
                Object[] expanded = new Object[newLength];
                for (int i = 0; i < size; i++) {
                    expanded[i] = entries[i];
                }
                entries = expanded;
            }

            int index = size;
            while (index > 0) {
                Entry<E> previous = entryAt(index - 1);
                if (!comesBefore(entry.deadlineNanos, previous.deadlineNanos, now)) {
                    break;
                }
                entries[index] = previous;
                index--;
            }
            entries[index] = entry;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Waits until an element is available, then removes and returns it.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                Entry<E> first = entryAt(0);
                long remainingNanos = first.deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    E value = removeHead().value;
                    lock.notifyAll();
                    return value;
                }

                long waitMillis = remainingNanos / NANOS_PER_MILLI;
                int waitNanos = (int) (remainingNanos % NANOS_PER_MILLI);
                lock.wait(waitMillis, waitNanos);
            }
        }
    }

    /**
     * Removes and returns an available element, or returns null if none is ready.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0 || entryAt(0).deadlineNanos - System.nanoTime() > 0) {
                return null;
            }
            E value = removeHead().value;
            lock.notifyAll();
            return value;
        }
    }

    /**
     * Returns the next available element without removing it, or null if none is ready.
     */
    public E peek() {
        synchronized (lock) {
            if (size == 0 || entryAt(0).deadlineNanos - System.nanoTime() > 0) {
                return null;
            }
            return entryAt(0).value;
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

    private boolean comesBefore(long candidateDeadline, long existingDeadline, long now) {
        long candidateRemaining = candidateDeadline - now;
        long existingRemaining = existingDeadline - now;

        if (candidateRemaining <= 0) {
            return existingRemaining > 0;
        }
        if (existingRemaining <= 0) {
            return false;
        }
        return candidateRemaining < existingRemaining;
    }

    @SuppressWarnings("unchecked")
    private Entry<E> entryAt(int index) {
        return (Entry<E>) entries[index];
    }

    private Entry<E> removeHead() {
        Entry<E> first = entryAt(0);
        for (int i = 1; i < size; i++) {
            entries[i - 1] = entries[i];
        }
        entries[--size] = null;
        return first;
    }
}
