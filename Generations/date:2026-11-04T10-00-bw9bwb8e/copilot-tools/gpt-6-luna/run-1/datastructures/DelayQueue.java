@DelayQueue.ThreadSafe
public final class DelayQueue<E> {
    @interface ThreadSafe {}

    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4;

    private static final class Node<E> {
        final E value;
        final long deadlineNanos;

        Node(E value, long deadlineNanos) {
            this.value = value;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Object lock = new Object();
    private Object[] heap = new Object[16];
    private int size;

    public void put(E value, long delayMillis) {
        offer(value, delayMillis);
    }

    public boolean offer(E value, long delayMillis) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        long delayNanos;
        if (delayMillis <= 0) {
            delayNanos = 0;
        } else if (delayMillis > MAX_DELAY_NANOS / 1_000_000L) {
            delayNanos = MAX_DELAY_NANOS;
        } else {
            delayNanos = delayMillis * 1_000_000L;
        }

        Node<E> node = new Node<E>(value, System.nanoTime() + delayNanos);
        synchronized (lock) {
            ensureCapacity();
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) / 2;
                Node<E> parentNode = nodeAt(parent);
                if (compare(parentNode, node) <= 0) {
                    break;
                }
                heap[index] = parentNode;
                index = parent;
            }
            heap[index] = node;
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

                Node<E> first = nodeAt(0);
                long remainingNanos = first.deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    E value = removeRoot().value;
                    lock.notifyAll();
                    return value;
                }

                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                lock.wait(millis, nanos);
            }
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0 || nodeAt(0).deadlineNanos - System.nanoTime() > 0) {
                return null;
            }

            E value = removeRoot().value;
            lock.notifyAll();
            return value;
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

    private void ensureCapacity() {
        if (size < heap.length) {
            return;
        }

        int newCapacity = heap.length <= Integer.MAX_VALUE / 2
                ? heap.length * 2
                : Integer.MAX_VALUE;
        if (newCapacity <= heap.length) {
            throw new OutOfMemoryError("DelayQueue capacity exceeded");
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = heap[i];
        }
        heap = expanded;
    }

    @SuppressWarnings("unchecked")
    private Node<E> nodeAt(int index) {
        return (Node<E>) heap[index];
    }

    private int compare(Node<E> left, Node<E> right) {
        long difference = left.deadlineNanos - right.deadlineNanos;
        return difference < 0 ? -1 : difference > 0 ? 1 : 0;
    }

    private Node<E> removeRoot() {
        Node<E> result = nodeAt(0);
        Node<E> last = nodeAt(--size);
        heap[size] = null;

        if (size > 0) {
            int index = 0;
            while (true) {
                int left = index * 2 + 1;
                if (left >= size) {
                    break;
                }

                int right = left + 1;
                int child = right < size && compare(nodeAt(right), nodeAt(left)) < 0
                        ? right
                        : left;
                if (compare(last, nodeAt(child)) <= 0) {
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
