/**
 * A bounded, thread-safe delay queue.
 *
 * Delays are nonnegative nanoseconds measured from successful insertion.
 * put and take block when necessary; offer and poll never block.
 * peek returns the earliest element even if its delay has not expired.
 *
 * As with System.nanoTime(), elapsed intervals must be less than
 * 2^63 nanoseconds. In particular, an expired element must not remain
 * in the queue for 2^63 nanoseconds or longer.
 */
@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        final long deadline;
        Node<T> next;

        Node(T value, long deadline) {
            this.value = value;
            this.deadline = deadline;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> head;
    private int size;

    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void put(E value, long delayNanos) throws InterruptedException {
        checkArguments(value, delayNanos);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            insert(value, delayNanos);
            monitor.notifyAll();
        }
    }

    public boolean offer(E value, long delayNanos) {
        checkArguments(value, delayNanos);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            insert(value, delayNanos);
            monitor.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                if (head == null) {
                    monitor.wait();
                } else {
                    long remaining = head.deadline - System.nanoTime();
                    if (remaining <= 0) {
                        E value = removeHead();
                        monitor.notifyAll();
                        return value;
                    }
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    monitor.wait(millis, nanos);
                }
            }
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (head == null || head.deadline - System.nanoTime() > 0) {
                return null;
            }
            E value = removeHead();
            monitor.notifyAll();
            return value;
        }
    }

    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (monitor) {
            if (head != null) {
                head = null;
                size = 0;
                monitor.notifyAll();
            }
        }
    }

    private void checkArguments(E value, long delayNanos) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (delayNanos < 0) {
            throw new IllegalArgumentException("delay must be nonnegative");
        }
    }

    // Called with monitor held.
    private void insert(E value, long delayNanos) {
        long now = System.nanoTime();
        Node<E> node = new Node<E>(value, now + delayNanos);
        Node<E> previous = null;
        Node<E> current = head;

        while (current != null && current.deadline - now <= delayNanos) {
            previous = current;
            current = current.next;
        }

        node.next = current;
        if (previous == null) {
            head = node;
        } else {
            previous.next = node;
        }
        size++;
    }

    // Called with monitor held and a nonempty queue.
    private E removeHead() {
        Node<E> node = head;
        head = node.next;
        size--;
        return node.value;
    }
}
