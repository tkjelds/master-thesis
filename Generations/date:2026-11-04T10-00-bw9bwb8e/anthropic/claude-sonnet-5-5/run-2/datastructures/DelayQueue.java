import java.lang.*;

@DelayQueue.ThreadSafe
public class DelayQueue<E> {
    @interface ThreadSafe {}


    private static final class Node {
        final Object value;
        final long deadline; // System.nanoTime based
        final long seq;

        Node(Object value, long deadline, long seq) {
            this.value = value;
            this.deadline = deadline;
            this.seq = seq;
        }
    }

    private final Object lock = new Object();
    private Node[] heap = new Node[16];
    private int size = 0;
    private long sequence = 0;

    // true if a should be dequeued before b
    private static boolean before(Node a, Node b) {
        long diff = a.deadline - b.deadline;
        if (diff != 0) {
            return diff < 0;
        }
        return a.seq < b.seq;
    }

    private void siftUp(int index, Node node) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            Node p = heap[parent];
            if (!before(node, p)) {
                break;
            }
            heap[index] = p;
            index = parent;
        }
        heap[index] = node;
    }

    private void siftDown(int index, Node node) {
        int half = size >>> 1;
        while (index < half) {
            int child = 2 * index + 1;
            int right = child + 1;
            if (right < size && before(heap[right], heap[child])) {
                child = right;
            }
            if (!before(heap[child], node)) {
                break;
            }
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = node;
    }

    private void grow() {
        Node[] n = new Node[heap.length * 2];
        for (int i = 0; i < size; i++) {
            n[i] = heap[i];
        }
        heap = n;
    }

    // must hold lock, queue non-empty
    private Node removeHead() {
        Node result = heap[0];
        size--;
        Node last = heap[size];
        heap[size] = null;
        if (size > 0) {
            siftDown(0, last);
        }
        return result;
    }

    /**
     * Inserts an element that becomes available after the given delay (nanoseconds).
     * Never blocks (queue is unbounded). Negative delays are treated as zero.
     */
    public void put(E element, long delayNanos) {
        if (element == null) {
            throw new NullPointerException();
        }
        if (delayNanos < 0) {
            delayNanos = 0;
        }
        synchronized (lock) {
            long deadline = System.nanoTime() + delayNanos;
            Node node = new Node(element, deadline, sequence++);
            if (size == heap.length) {
                grow();
            }
            size++;
            siftUp(size - 1, node);
            lock.notifyAll();
        }
    }

    /** Convenience: delay in milliseconds. */
    public void putMillis(E element, long delayMillis) {
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        if (delayMillis > Long.MAX_VALUE / 1_000_000L) {
            delayMillis = Long.MAX_VALUE / 1_000_000L / 4;
        }
        put(element, delayMillis * 1_000_000L);
    }

    /**
     * Retrieves and removes the head, blocking until an element's delay has expired.
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                } else {
                    long remaining = heap[0].deadline - System.nanoTime();
                    if (remaining <= 0) {
                        Node n = removeHead();
                        if (size > 0) {
                            lock.notifyAll();
                        }
                        return (E) n.value;
                    }
                    lock.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                }
            }
        }
    }

    /**
     * Retrieves and removes the head if its delay has expired, otherwise returns null immediately.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            if (heap[0].deadline - System.nanoTime() > 0) {
                return null;
            }
            return (E) removeHead().value;
        }
    }

    /**
     * Waits up to timeoutNanos for an expired element; returns null on timeout.
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeoutNanos) throws InterruptedException {
        synchronized (lock) {
            long start = System.nanoTime();
            long left = timeoutNanos;
            while (true) {
                long waitFor;
                if (size == 0) {
                    if (left <= 0) {
                        return null;
                    }
                    waitFor = left;
                } else {
                    long remaining = heap[0].deadline - System.nanoTime();
                    if (remaining <= 0) {
                        Node n = removeHead();
                        if (size > 0) {
                            lock.notifyAll();
                        }
                        return (E) n.value;
                    }
                    if (left <= 0) {
                        return null;
                    }
                    waitFor = remaining < left ? remaining : left;
                }
                lock.wait(waitFor / 1_000_000L, (int) (waitFor % 1_000_000L));
                left = timeoutNanos - (System.nanoTime() - start);
            }
        }
    }

    /** Number of elements, including those whose delay has not yet expired. */
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
}
