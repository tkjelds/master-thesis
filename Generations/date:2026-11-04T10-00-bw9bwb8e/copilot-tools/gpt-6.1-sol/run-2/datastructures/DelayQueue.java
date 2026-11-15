/**
 * An unbounded, thread-safe queue of elements with insertion-relative delays.
 *
 * All delays and timeouts are measured in nanoseconds. Nonpositive delays make
 * an element immediately available. Equal expiration times preserve insertion
 * order. Null elements are not permitted.
 *
 * poll() and take() remove only expired elements; peek() may return an
 * unexpired element. take() blocks until an element expires. Timed poll()
 * blocks until an element expires or its timeout elapses.
 */
@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T element;

        // Remaining delay relative to the preceding node, or now for the head.
        long delay;
        Node<T> next;

        Node(T element, long delay) {
            this.element = element;
            this.delay = delay;
        }
    }

    private final Object monitor = new Object();
    private Node<E> head;
    private int count;
    private long lastTick;

    public DelayQueue() {
        lastTick = System.nanoTime();
    }

    /**
     * Inserts an element without waiting for capacity.
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
            refresh();

            Node<E> previous = null;
            Node<E> current = head;
            long remaining = node.delay;

            while (current != null && remaining >= current.delay) {
                remaining -= current.delay;
                previous = current;
                current = current.next;
            }

            node.delay = remaining;
            node.next = current;

            if (current != null) {
                current.delay -= remaining;
            }

            if (previous == null) {
                head = node;
            } else {
                previous.next = node;
            }

            count++;
            monitor.notifyAll();
            return true;
        }
    }

    /**
     * Equivalent to offer; this unbounded queue never waits for capacity.
     */
    public void put(E element, long delayNanos) {
        offer(element, delayNanos);
    }

    /**
     * Removes the earliest expired element, or returns null immediately.
     */
    public E poll() {
        synchronized (monitor) {
            refresh();
            if (head == null || head.delay != 0L) {
                return null;
            }
            return removeExpiredHead();
        }
    }

    /**
     * Waits for and removes the earliest expired element.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                refresh();

                if (head != null && head.delay == 0L) {
                    return removeExpiredHead();
                }

                if (head == null) {
                    monitor.wait();
                } else {
                    awaitNanos(head.delay);
                }
            }
        }
    }

    /**
     * Removes an expired element, waiting at most timeoutNanos if necessary.
     * A nonpositive timeout performs an immediate poll.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeoutNanos) throws InterruptedException {
        long remaining = timeoutNanos > 0L ? timeoutNanos : 0L;
        long previousTick = System.nanoTime();

        synchronized (monitor) {
            while (true) {
                long now = refresh();
                long elapsed = now - previousTick;
                previousTick = now;

                if (elapsed < 0L || elapsed >= remaining) {
                    remaining = 0L;
                } else {
                    remaining -= elapsed;
                }

                if (head != null && head.delay == 0L) {
                    return removeExpiredHead();
                }

                if (remaining == 0L) {
                    return null;
                }

                long waitNanos = remaining;
                if (head != null && head.delay < waitNanos) {
                    waitNanos = head.delay;
                }
                awaitNanos(waitNanos);
            }
        }
    }

    /**
     * Returns the earliest element without removing it, even if unexpired.
     */
    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.element;
        }
    }

    /**
     * Returns the number of elements, including unexpired elements.
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
            lastTick = System.nanoTime();
            monitor.notifyAll();
        }
    }

    /*
     * Called only with monitor held. Relative delays avoid overflowing absolute
     * deadlines, including when a requested delay is Long.MAX_VALUE.
     */
    private long refresh() {
        long now = System.nanoTime();
        long elapsed = now - lastTick;
        lastTick = now;

        // An interval exceeding the signed range expires every supported delay.
        if (elapsed < 0L) {
            elapsed = Long.MAX_VALUE;
        }

        Node<E> current = head;
        while (current != null && elapsed > 0L) {
            if (current.delay > elapsed) {
                current.delay -= elapsed;
                break;
            }

            elapsed -= current.delay;
            current.delay = 0L;
            current = current.next;
        }

        return now;
    }

    // Called only with monitor held and an expired head.
    private E removeExpiredHead() {
        Node<E> removed = head;
        head = removed.next;
        removed.next = null;
        count--;
        monitor.notifyAll();
        return removed.element;
    }

    // Called only with monitor held and a strictly positive duration.
    private void awaitNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1_000_000L;
        int remainder = (int) (nanos % 1_000_000L);
        monitor.wait(millis, remainder);
    }
}
