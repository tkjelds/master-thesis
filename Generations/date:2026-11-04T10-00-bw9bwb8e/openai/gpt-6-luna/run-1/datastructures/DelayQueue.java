@DelayQueue.ThreadSafe
public final class DelayQueue<E extends DelayQueue.Delayed> {
    @interface ThreadSafe {}

    /**
     * An element reports its initial delay in nanoseconds when it is offered.
     * The queue records that delay as an expiration time.
     */
    public interface Delayed {
        long getDelayNanos();
    }

    private static final class Node<T> {
        final T value;
        final long deadline;

        Node(T value, long deadline) {
            this.value = value;
            this.deadline = deadline;
        }
    }

    private final Object lock = new Object();
    private Node<?>[] heap = new Node<?>[16];
    private int size;

    /**
     * Inserts an element. This queue is unbounded, so insertion never waits
     * for capacity.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        long delay = element.getDelayNanos();
        if (delay < 0) {
            delay = 0;
        }
        long deadline = System.nanoTime() + delay;

        synchronized (lock) {
            ensureCapacity(size + 1);
            Node<E> node = new Node<E>(element, deadline);
            siftUp(size, node);
            size++;
            lock.notifyAll();
            return true;
        }
    }

    public void put(E element) {
        offer(element);
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }

                long remaining = ((Node<?>) heap[0]).deadline - System.nanoTime();
                if (remaining <= 0) {
                    return removeAt(0).value;
                }

                waitNanos(remaining);
            }
        }
    }

    /**
     * Waits up to timeoutNanos for an expired element. A nonpositive timeout
     * performs an immediate poll.
     */
    public E poll(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos <= 0) {
            return poll();
        }

        long timeoutDeadline = System.nanoTime() + timeoutNanos;
        synchronized (lock) {
            while (true) {
                if (size > 0) {
                    Node<?> first = heap[0];
                    long delayRemaining = first.deadline - System.nanoTime();
                    if (delayRemaining <= 0) {
                        return removeAt(0).value;
                    }

                    long timeoutRemaining = timeoutDeadline - System.nanoTime();
                    if (timeoutRemaining <= 0) {
                        return null;
                    }
                    waitNanos(delayRemaining < timeoutRemaining
                            ? delayRemaining : timeoutRemaining);
                } else {
                    long timeoutRemaining = timeoutDeadline - System.nanoTime();
                    if (timeoutRemaining <= 0) {
                        return null;
                    }
                    waitNanos(timeoutRemaining);
                }
            }
        }
    }

    /**
     * Returns and removes an expired element, or null if none is currently due.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0 || ((Node<?>) heap[0]).deadline - System.nanoTime() > 0) {
                return null;
            }
            return removeAt(0).value;
        }
    }

    /**
     * Returns the earliest-expiring element, whether or not it has expired.
     */
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Node<E> node = (Node<E>) heap[0];
            return node.value;
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    public boolean remove(Object target) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                Node<?> node = heap[i];
                if (node.value == target || (target != null && target.equals(node.value))) {
                    removeAt(i);
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    private void waitNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        lock.wait(millis, extraNanos);
    }

    private void ensureCapacity(int needed) {
        if (needed <= heap.length) {
            return;
        }
        int newCapacity = heap.length * 2;
        if (newCapacity < needed) {
            newCapacity = needed;
        }
        Node<?>[] replacement = new Node<?>[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = heap[i];
        }
        heap = replacement;
    }

    private static boolean earlier(long firstDeadline, long secondDeadline) {
        return firstDeadline - secondDeadline < 0;
    }

    @SuppressWarnings("unchecked")
    private void siftUp(int index, Node<E> node) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            Node<E> parentNode = (Node<E>) heap[parent];
            if (!earlier(node.deadline, parentNode.deadline)) {
                break;
            }
            heap[index] = parentNode;
            index = parent;
        }
        heap[index] = node;
    }

    @SuppressWarnings("unchecked")
    private void siftDown(int index, Node<E> node) {
        int half = size >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;
            Node<E> childNode = (Node<E>) heap[child];

            if (right < size) {
                Node<E> rightNode = (Node<E>) heap[right];
                if (earlier(rightNode.deadline, childNode.deadline)) {
                    child = right;
                    childNode = rightNode;
                }
            }

            if (!earlier(childNode.deadline, node.deadline)) {
                break;
            }
            heap[index] = childNode;
            index = child;
        }
        heap[index] = node;
    }

    @SuppressWarnings("unchecked")
    private Node<E> removeAt(int index) {
        Node<E> removed = (Node<E>) heap[index];
        int lastIndex = size - 1;
        Node<E> last = (Node<E>) heap[lastIndex];
        heap[lastIndex] = null;
        size--;

        if (index != lastIndex) {
            if (index > 0) {
                int parent = (index - 1) >>> 1;
                Node<E> parentNode = (Node<E>) heap[parent];
                if (earlier(last.deadline, parentNode.deadline)) {
                    siftUp(index, last);
                    return removed;
                }
            }
            siftDown(index, last);
        }

        lock.notifyAll();
        return removed;
    }
}
