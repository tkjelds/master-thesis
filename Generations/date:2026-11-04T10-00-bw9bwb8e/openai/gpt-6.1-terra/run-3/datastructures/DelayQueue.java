/**
 * A bounded, thread-safe delay queue.
 *
 * Delays are specified in milliseconds and begin when an element is inserted.
 * Negative delays and null elements are rejected.
 *
 * put waits for space; offer returns false when full.
 * take waits for an expired element; poll returns null if none is expired.
 * peek returns the earliest element, whether or not it has expired.
 */
@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_SECOND = 1000000000L;
    private static final int NANOS_PER_MILLI = 1000000;

    private static final class Node<T> {
        final T value;
        final long seconds;
        final int nanos;

        Node(T value, long seconds, int nanos) {
            this.value = value;
            this.seconds = seconds;
            this.nanos = nanos;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E>[] heap;
    private int size;

    // A monotonic clock represented as seconds and nanoseconds since construction.
    private long lastTick;
    private long clockSeconds;
    private int clockNanos;

    public DelayQueue() {
        this(Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        int initialSize = capacity < 16 ? capacity : 16;
        this.heap = (Node<E>[]) new Node<?>[initialSize];
        this.lastTick = System.nanoTime();
    }

    public void put(E element, long delayMillis) throws InterruptedException {
        validate(element, delayMillis);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            insert(element, delayMillis);
            monitor.notifyAll();
        }
    }

    public boolean offer(E element, long delayMillis) {
        validate(element, delayMillis);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            insert(element, delayMillis);
            monitor.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                while (size == 0) {
                    monitor.wait();
                }

                updateClock();
                Node<E> first = heap[0];
                if (isDue(first)) {
                    E value = removeFirst();
                    monitor.notifyAll();
                    return value;
                }

                long seconds = first.seconds - clockSeconds;
                int nanos = first.nanos - clockNanos;
                if (nanos < 0) {
                    seconds--;
                    nanos += (int) NANOS_PER_SECOND;
                }

                long millis = seconds * 1000L + nanos / NANOS_PER_MILLI;
                int extraNanos = nanos % NANOS_PER_MILLI;
                monitor.wait(millis, extraNanos);
            }
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            updateClock();
            if (!isDue(heap[0])) {
                return null;
            }
            E value = removeFirst();
            monitor.notifyAll();
            return value;
        }
    }

    public E peek() {
        synchronized (monitor) {
            return size == 0 ? null : heap[0].value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
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

    private void validate(E element, long delayMillis) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delay must not be negative");
        }
    }

    // Called only while holding monitor.
    private void updateClock() {
        long now = System.nanoTime();
        long elapsed = now - lastTick;
        lastTick = now;

        // As with nanoTime itself, elapsed intervals must be less than 2^63 ns.
        if (elapsed > 0) {
            clockSeconds += elapsed / NANOS_PER_SECOND;
            long nanos = (long) clockNanos + elapsed % NANOS_PER_SECOND;
            if (nanos >= NANOS_PER_SECOND) {
                clockSeconds++;
                nanos -= NANOS_PER_SECOND;
            }
            clockNanos = (int) nanos;
        }
    }

    private boolean isDue(Node<E> node) {
        return node.seconds < clockSeconds
                || (node.seconds == clockSeconds && node.nanos <= clockNanos);
    }

    private boolean before(Node<E> a, Node<E> b) {
        return a.seconds < b.seconds
                || (a.seconds == b.seconds && a.nanos < b.nanos);
    }

    private void insert(E element, long delayMillis) {
        ensureSpace();
        updateClock();

        long seconds = clockSeconds + delayMillis / 1000L;
        int nanos = clockNanos
                + (int) (delayMillis % 1000L) * NANOS_PER_MILLI;
        if (nanos >= NANOS_PER_SECOND) {
            seconds++;
            nanos -= (int) NANOS_PER_SECOND;
        }

        Node<E> node = new Node<E>(element, seconds, nanos);
        int index = size++;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (!before(node, heap[parent])) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = node;
    }

    @SuppressWarnings("unchecked")
    private void ensureSpace() {
        if (size < heap.length) {
            return;
        }
        long newLength = (long) heap.length * 2L;
        if (newLength > capacity) {
            newLength = capacity;
        }
        Node<E>[] replacement = (Node<E>[]) new Node<?>[(int) newLength];
        for (int i = 0; i < size; i++) {
            replacement[i] = heap[i];
        }
        heap = replacement;
    }

    private E removeFirst() {
        E result = heap[0].value;
        int newSize = --size;
        Node<E> last = heap[newSize];
        heap[newSize] = null;

        if (newSize != 0) {
            int index = 0;
            int half = newSize >>> 1;
            while (index < half) {
                int child = index * 2 + 1;
                int right = child + 1;
                if (right < newSize && before(heap[right], heap[child])) {
                    child = right;
                }
                if (!before(heap[child], last)) {
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
