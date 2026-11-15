/**
 * An unbounded, thread-safe queue whose elements become available after a delay.
 *
 * Delays and timeouts are measured in nanoseconds using System.nanoTime().
 * Nonpositive delays make an element immediately available. Null elements are
 * prohibited. Elements with equal availability times retain insertion order.
 *
 * offer never waits for capacity. take blocks until an element is available.
 * poll without a timeout never blocks; timed poll waits at most its timeout,
 * excluding time spent acquiring the monitor and scheduling delays.
 *
 * Timing assumes consecutive clock samples are less than 2^63 nanoseconds apart.
 */
@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_MILLISECOND = 1000000L;

    private static final class Node<T> {
        final T value;
        long remainingNanos;
        Node<T> next;

        Node(T value, long remainingNanos) {
            this.value = value;
            this.remainingNanos = remainingNanos;
        }
    }

    private final Object monitor = new Object();
    private Node<E> head;
    private int count;
    private long lastUpdateNanos = System.nanoTime();

    public DelayQueue() {
    }

    /**
     * Inserts an element with the specified delay, measured from insertion.
     * Always returns true unless an exception is thrown.
     */
    public boolean offer(E element, long delayNanos) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (monitor) {
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException("Queue size limit reached");
            }

            Node<E> node = new Node<E>(
                    element, delayNanos > 0L ? delayNanos : 0L);
            advanceTime();

            if (head == null || node.remainingNanos < head.remainingNanos) {
                node.next = head;
                head = node;
            } else {
                Node<E> current = head;
                while (current.next != null
                        && current.next.remainingNanos <= node.remainingNanos) {
                    current = current.next;
                }
                node.next = current.next;
                current.next = node;
            }

            count++;
            monitor.notifyAll();
            return true;
        }
    }

    /**
     * Removes the earliest available element, waiting if necessary.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                advanceTime();

                if (head == null) {
                    monitor.wait();
                } else if (head.remainingNanos == 0L) {
                    return removeHead();
                } else {
                    waitForNanos(head.remainingNanos);
                }
            }
        }
    }

    /**
     * Removes an available element, or returns null immediately if none exists.
     */
    public E poll() {
        synchronized (monitor) {
            advanceTime();
            if (head == null || head.remainingNanos != 0L) {
                return null;
            }
            return removeHead();
        }
    }

    /**
     * Removes an available element, waiting up to timeoutNanos if necessary.
     * A nonpositive timeout is equivalent to an immediate poll.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeoutNanos) throws InterruptedException {
        synchronized (monitor) {
            long remainingTimeout = timeoutNanos > 0L ? timeoutNanos : 0L;
            long previousSample = System.nanoTime();

            while (true) {
                long now = advanceTime();
                long elapsed = now - previousSample;
                previousSample = now;

                if (elapsed > 0L) {
                    remainingTimeout = elapsed >= remainingTimeout
                            ? 0L : remainingTimeout - elapsed;
                }

                if (head != null && head.remainingNanos == 0L) {
                    return removeHead();
                }
                if (remainingTimeout == 0L) {
                    return null;
                }

                long waitNanos = remainingTimeout;
                if (head != null && head.remainingNanos < waitNanos) {
                    waitNanos = head.remainingNanos;
                }
                waitForNanos(waitNanos);
            }
        }
    }

    /**
     * Returns the earliest element without removing it, even if not yet
     * available, or null if the queue is empty.
     */
    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

    /**
     * Counts all queued elements, including those not yet available.
     */
    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            count = 0;
            lastUpdateNanos = System.nanoTime();
            monitor.notifyAll();
        }
    }

    /*
     * All private helpers require ownership of monitor. Keeping remaining
     * durations instead of absolute deadlines avoids deadline-addition overflow.
     */
    private long advanceTime() {
        long now = System.nanoTime();
        long elapsed = now - lastUpdateNanos;
        lastUpdateNanos = now;

        if (elapsed > 0L) {
            Node<E> current = head;
            while (current != null) {
                current.remainingNanos = elapsed >= current.remainingNanos
                        ? 0L : current.remainingNanos - elapsed;
                current = current.next;
            }
        }

        return now;
    }

    private E removeHead() {
        Node<E> removed = head;
        head = removed.next;
        removed.next = null;
        count--;
        monitor.notifyAll();
        return removed.value;
    }

    private void waitForNanos(long nanos) throws InterruptedException {
        long millis = nanos / NANOS_PER_MILLISECOND;
        int remainder = (int) (nanos % NANOS_PER_MILLISECOND);
        monitor.wait(millis, remainder);
    }
}
