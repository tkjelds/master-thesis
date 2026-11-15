/**
 * A bounded, thread-safe delay queue.
 *
 * Delays are nonnegative nanoseconds measured from insertion. For put, the
 * delay starts after space becomes available. Null elements are not permitted.
 * Elements with equal deadlines may be returned in any order.
 *
 * offer and poll do not wait. put waits for capacity, and take waits until an
 * element is available and its delay has elapsed.
 */
@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final class Entry<T> {
        final T value;
        final long era;
        final long tick;

        Entry(T value, long era, long tick) {
            this.value = value;
            this.era = era;
            this.tick = tick;
        }
    }

    private final Object monitor = new Object();
    private final Entry<E>[] heap;
    private int size;

    /*
     * Logical time uses eras of 2^63 nanoseconds. This keeps old expired
     * entries correctly ordered even when nanoTime wraps.
     */
    private long lastNanos;
    private long clockEra;
    private long clockTick;

    @SuppressWarnings("unchecked")
    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        heap = (Entry<E>[]) new Entry<?>[capacity];
        lastNanos = System.nanoTime();
    }

    public boolean offer(E value, long delayNanos) {
        validate(value, delayNanos);
        synchronized (monitor) {
            if (size == heap.length) {
                return false;
            }
            insert(value, delayNanos);
            monitor.notifyAll();
            return true;
        }
    }

    public void put(E value, long delayNanos) throws InterruptedException {
        validate(value, delayNanos);
        synchronized (monitor) {
            while (size == heap.length) {
                monitor.wait();
            }
            insert(value, delayNanos);
            monitor.notifyAll();
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            advanceClock();
            if (compareTime(heap[0].era, heap[0].tick, clockEra, clockTick) > 0) {
                return null;
            }
            E value = removeRoot();
            monitor.notifyAll();
            return value;
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            for (;;) {
                while (size == 0) {
                    monitor.wait();
                }

                advanceClock();
                Entry<E> first = heap[0];
                if (compareTime(first.era, first.tick, clockEra, clockTick) <= 0) {
                    E value = removeRoot();
                    monitor.notifyAll();
                    return value;
                }

                long remaining = remainingNanos(first);
                monitor.wait(remaining / NANOS_PER_MILLI,
                             (int) (remaining % NANOS_PER_MILLI));
            }
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return heap.length - size;
        }
    }

    private void validate(E value, long delayNanos) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (delayNanos < 0) {
            throw new IllegalArgumentException("delay must be nonnegative");
        }
    }

    /* Called only while holding monitor. */
    private void advanceClock() {
        long now = System.nanoTime();
        long elapsed = now - lastNanos;
        if (elapsed < 0) {
            throw new IllegalStateException(
                "monotonic clock moved backwards or was not sampled for too long");
        }

        long space = Long.MAX_VALUE - clockTick;
        if (elapsed > space) {
            if (clockEra == Long.MAX_VALUE) {
                throw new IllegalStateException("logical clock overflow");
            }
            clockEra++;
            clockTick = elapsed - space - 1;
        } else {
            clockTick += elapsed;
        }
        lastNanos = now;
    }

    /* Called only while holding monitor. */
    private void insert(E value, long delayNanos) {
        advanceClock();

        long deadlineEra = clockEra;
        long deadlineTick;
        long space = Long.MAX_VALUE - clockTick;
        if (delayNanos > space) {
            if (deadlineEra == Long.MAX_VALUE) {
                throw new IllegalStateException("deadline overflow");
            }
            deadlineEra++;
            deadlineTick = delayNanos - space - 1;
        } else {
            deadlineTick = clockTick + delayNanos;
        }

        Entry<E> entry = new Entry<E>(value, deadlineEra, deadlineTick);
        int index = size++;
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (compare(entry, heap[parent]) >= 0) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = entry;
    }

    /* Called only while holding monitor, with a nonempty heap. */
    private E removeRoot() {
        E value = heap[0].value;
        int lastIndex = --size;
        Entry<E> last = heap[lastIndex];
        heap[lastIndex] = null;

        if (lastIndex != 0) {
            int index = 0;
            while (index < size / 2) {
                int left = index * 2 + 1;
                int right = left + 1;
                int child = left;
                if (right < size && compare(heap[right], heap[left]) < 0) {
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
        return value;
    }

    /* The deadline is in the future and at most Long.MAX_VALUE ns away. */
    private long remainingNanos(Entry<E> entry) {
        if (entry.era == clockEra) {
            return entry.tick - clockTick;
        }
        return (Long.MAX_VALUE - clockTick) + entry.tick + 1;
    }

    private static int compare(Entry<?> a, Entry<?> b) {
        return compareTime(a.era, a.tick, b.era, b.tick);
    }

    private static int compareTime(long eraA, long tickA, long eraB, long tickB) {
        if (eraA != eraB) {
            return eraA < eraB ? -1 : 1;
        }
        if (tickA != tickB) {
            return tickA < tickB ? -1 : 1;
        }
        return 0;
    }
}
