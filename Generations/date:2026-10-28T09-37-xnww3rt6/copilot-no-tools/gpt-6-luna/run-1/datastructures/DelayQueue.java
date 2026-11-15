@DelayQueue.ThreadSafe
public final class DelayQueue<T> {
    @interface ThreadSafe {}

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final class Node<E> {
        final E item;
        final long deadline;
        Node<E> next;

        Node(E item, long deadline) {
            this.item = item;
            this.deadline = deadline;
        }
    }

    private final Object monitor = new Object();
    private Node<T> head;
    private int size;

    /**
     * Adds an item that becomes available after delayMillis.
     * Negative or zero delays make the item immediately available.
     * Null items are not permitted.
     */
    public void put(T item, long delayMillis) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        long delayNanos;
        if (delayMillis <= 0) {
            delayNanos = 0L;
        } else if (delayMillis > Long.MAX_VALUE / NANOS_PER_MILLI) {
            delayNanos = Long.MAX_VALUE;
        } else {
            delayNanos = delayMillis * NANOS_PER_MILLI;
        }

        synchronized (monitor) {
            long deadline = System.nanoTime() + delayNanos;
            Node<T> node = new Node<T>(item, deadline);

            if (head == null || deadline - head.deadline < 0L) {
                node.next = head;
                head = node;
            } else {
                Node<T> current = head;
                while (current.next != null
                        && deadline - current.next.deadline >= 0L) {
                    current = current.next;
                }
                node.next = current.next;
                current.next = node;
            }

            size++;
            monitor.notifyAll();
        }
    }

    /**
     * Removes and returns the earliest item once its delay has elapsed.
     * Blocks while the queue is empty or the earliest item is not yet available.
     */
    public T take() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                while (head == null) {
                    monitor.wait();
                }

                long remaining = head.deadline - System.nanoTime();
                if (remaining <= 0L) {
                    Node<T> node = head;
                    head = node.next;
                    size--;
                    return node.item;
                }

                long millis = remaining / NANOS_PER_MILLI;
                int nanos = (int) (remaining % NANOS_PER_MILLI);
                monitor.wait(millis, nanos);
            }
        }
    }

    /**
     * Removes and returns the earliest item if its delay has elapsed.
     * Returns null if the queue is empty or the earliest item is not yet available.
     */
    public T poll() {
        synchronized (monitor) {
            if (head == null || head.deadline - System.nanoTime() > 0L) {
                return null;
            }

            Node<T> node = head;
            head = node.next;
            size--;
            return node.item;
        }
    }

    /**
     * Returns the earliest scheduled item, whether or not its delay has elapsed.
     * Returns null if the queue is empty.
     */
    public T peek() {
        synchronized (monitor) {
            return head == null ? null : head.item;
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

    public void clear() {
        synchronized (monitor) {
            head = null;
            size = 0;
            monitor.notifyAll();
        }
    }
}
