@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    /*
     * Delays are measured in nanoseconds from successful insertion.
     * offer returns false when full; put waits for space.
     * poll returns null unless an element is ready; take waits for one.
     * peek returns the earliest element, even if its delay has not elapsed.
     *
     * Deadline comparisons use System.nanoTime's wrapping arithmetic. As with
     * nanoTime interval measurements generally, they assume that relevant
     * elapsed intervals do not exceed half of the long nanosecond range.
     */
    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 2;

    private final Object lock = new Object();
    private final int capacity;
    private Object[] elements;
    private long[] deadlines;
    private int size;

    public DelayQueue() {
        this(Integer.MAX_VALUE);
    }

    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        int initialCapacity = capacity < 16 ? capacity : 16;
        elements = new Object[initialCapacity];
        deadlines = new long[initialCapacity];
    }

    public boolean offer(E element, long delayNanos) {
        validate(element, delayNanos);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            insert(element, delayNanos);
            lock.notifyAll();
            return true;
        }
    }

    public void put(E element, long delayNanos) throws InterruptedException {
        validate(element, delayNanos);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            insert(element, delayNanos);
            lock.notifyAll();
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0 || deadlines[0] - System.nanoTime() > 0) {
                return null;
            }
            E result = removeRoot();
            lock.notifyAll();
            return result;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                } else {
                    long remaining = deadlines[0] - System.nanoTime();
                    if (remaining <= 0) {
                        E result = removeRoot();
                        lock.notifyAll();
                        return result;
                    }
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    lock.wait(millis, nanos);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[0];
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                for (int i = 0; i < size; i++) {
                    elements[i] = null;
                }
                size = 0;
                lock.notifyAll();
            }
        }
    }

    private void validate(E element, long delayNanos) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        if (delayNanos < 0 || delayNanos > MAX_DELAY_NANOS) {
            throw new IllegalArgumentException("delay out of range");
        }
    }

    /* Called only while holding lock. */
    private void insert(E element, long delayNanos) {
        if (size == elements.length) {
            grow();
        }

        long deadline = System.nanoTime() + delayNanos;
        int index = size++;
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (!earlier(deadline, deadlines[parent])) {
                break;
            }
            elements[index] = elements[parent];
            deadlines[index] = deadlines[parent];
            index = parent;
        }
        elements[index] = element;
        deadlines[index] = deadline;
    }

    /* Called only while holding lock, with size greater than zero. */
    @SuppressWarnings("unchecked")
    private E removeRoot() {
        E result = (E) elements[0];
        int last = --size;
        Object element = elements[last];
        long deadline = deadlines[last];
        elements[last] = null;

        if (last != 0) {
            int index = 0;
            int half = size / 2;
            while (index < half) {
                int child = index * 2 + 1;
                int right = child + 1;
                if (right < size && earlier(deadlines[right], deadlines[child])) {
                    child = right;
                }
                if (!earlier(deadlines[child], deadline)) {
                    break;
                }
                elements[index] = elements[child];
                deadlines[index] = deadlines[child];
                index = child;
            }
            elements[index] = element;
            deadlines[index] = deadline;
        }
        return result;
    }

    private boolean earlier(long first, long second) {
        return first - second < 0;
    }

    /* Called only while holding lock and when the queue is not full. */
    private void grow() {
        long doubled = (long) elements.length * 2;
        int newLength = doubled > capacity ? capacity : (int) doubled;
        Object[] newElements = new Object[newLength];
        long[] newDeadlines = new long[newLength];
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[i];
            newDeadlines[i] = deadlines[i];
        }
        elements = newElements;
        deadlines = newDeadlines;
    }
}
