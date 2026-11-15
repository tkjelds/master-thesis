/**
 * An unbounded, thread-safe delay queue.
 *
 * Delays and timeouts are measured in nanoseconds using System.nanoTime().
 * Nonpositive delays make an element immediately available. Null elements
 * are prohibited. Equal expiration times preserve insertion order.
 *
 * take() blocks until an element expires. poll(timeoutNanos) blocks until
 * an element expires or its timeout elapses. Interrupting a monitor wait
 * causes InterruptedException without removing an element.
 *
 * peek() returns the earliest element even if it has not expired.
 * size() includes both expired and unexpired elements.
 *
 * Relative delays avoid overflowing absolute expiration timestamps.
 * As with nanoTime-based timing generally, consecutive clock observations
 * must be less than 2^63 nanoseconds apart.
 */
@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        long delay;
        Node<T> next;

        Node(T value, long delay) {
            this.value = value;
            this.delay = delay;
        }
    }

    private final Object monitor = new Object();
    private Node<E> head;
    private int count;
    private long lastObservation = System.nanoTime();

    /**
     * Inserts an element with the specified delay. This operation never waits
     * for capacity, although acquiring the monitor may block.
     */
    public boolean offer(E element, long delayNanos) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (monitor) {
            if (count == 2147483647) {
                throw new IllegalStateException("Queue size limit reached");
            }

            Node<E> inserted = new Node<E>(
                    element, delayNanos > 0L ? delayNanos : 0L);

            advance(System.nanoTime());

            Node<E> previous = null;
            Node<E> current = head;
            long remaining = inserted.delay;

            // Each node stores its delay relative to its predecessor.
            while (current != null && remaining >= current.delay) {
                remaining -= current.delay;
                previous = current;
                current = current.next;
            }

            inserted.delay = remaining;
            inserted.next = current;

            if (current != null) {
                current.delay -= remaining;
            }

            if (previous == null) {
                head = inserted;
            } else {
                previous.next = inserted;
            }

            count++;
            monitor.notifyAll();
            return true;
        }
    }

    /**
     * Removes the earliest expired element, waiting if necessary.
     */
    public E take() throws InterruptedException {
        synchronized (monitor) {
            advance(System.nanoTime());

            while (head == null || head.delay != 0L) {
                if (head == null) {
                    monitor.wait();
                } else {
                    awaitNanos(head.delay);
                }
                advance(System.nanoTime());
            }

            return removeExpiredHead();
        }
    }

    /**
     * Removes an expired element immediately, or returns null.
     */
    public E poll() {
        synchronized (monitor) {
            advance(System.nanoTime());

            if (head == null || head.delay != 0L) {
                return null;
            }

            return removeExpiredHead();
        }
    }

    /**
     * Removes an expired element, waiting up to timeoutNanos if necessary.
     * A nonpositive timeout performs an immediate poll.
     */
    public E poll(long timeoutNanos) throws InterruptedException {
        long previous = System.nanoTime();

        synchronized (monitor) {
            long remaining = timeoutNanos;

            while (true) {
                long now = System.nanoTime();
                advance(now);

                if (head != null && head.delay == 0L) {
                    return removeExpiredHead();
                }

                if (remaining <= 0L) {
                    return null;
                }

                long elapsed = now - previous;
                if (elapsed >= remaining) {
                    return null;
                }
                remaining -= elapsed;
                previous = now;

                long waitNanos = remaining;
                if (head != null && head.delay < waitNanos) {
                    waitNanos = head.delay;
                }

                awaitNanos(waitNanos);
            }
        }
    }

    /**
     * Returns the earliest element without removing it, or null if empty.
     * The returned element need not have expired.
     */
    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

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
            lastObservation = System.nanoTime();
            monitor.notifyAll();
        }
    }

    // All private helpers are called only while holding monitor.
    private void advance(long now) {
        long elapsed = now - lastObservation;
        lastObservation = now;

        Node<E> current = head;
        while (current != null && elapsed > 0L) {
            if (current.delay > elapsed) {
                current.delay -= elapsed;
                return;
            }

            elapsed -= current.delay;
            current.delay = 0L;
            current = current.next;
        }
    }

    private E removeExpiredHead() {
        Node<E> removed = head;
        head = removed.next;
        removed.next = null;
        count--;
        monitor.notifyAll();
        return removed.value;
    }

    private void awaitNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1000000L;
        int remainder = (int) (nanos % 1000000L);
        monitor.wait(millis, remainder);
    }
}
