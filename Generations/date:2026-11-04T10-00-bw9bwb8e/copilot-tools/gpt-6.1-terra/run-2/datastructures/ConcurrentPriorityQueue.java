/**
 * A bounded, thread-safe min-priority queue. The ordering must be stable and
 * transitive while elements are in the queue. Equal-priority elements may be
 * removed in any order. Null elements are not permitted.
 *
 * The ordering must not call operations on this queue or depend on another
 * thread calling them. Such reentrant calls are rejected.
 */
@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E> {
    @interface ThreadSafe {}

    public interface Ordering<T> {
        int compare(T first, T second);
    }

    private final Object lock = new Object();
    private final Object[] heap;
    private final Ordering<? super E> ordering;
    private int size;
    private boolean comparing;

    public ConcurrentPriorityQueue(int capacity, Ordering<? super E> ordering) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (ordering == null) {
            throw new NullPointerException("ordering");
        }
        this.heap = new Object[capacity];
        this.ordering = ordering;
    }

    /** Returns false if the queue is full. */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        synchronized (lock) {
            checkReentrancy();
            if (size == heap.length) {
                return false;
            }
            insert(element);
            lock.notifyAll();
            return true;
        }
    }

    /** Waits for space if the queue is full. */
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
        }
        synchronized (lock) {
            checkReentrancy();
            while (size == heap.length) {
                lock.wait();
            }
            insert(element);
            lock.notifyAll();
        }
    }

    /** Returns null if the queue is empty. */
    public E poll() {
        synchronized (lock) {
            checkReentrancy();
            if (size == 0) {
                return null;
            }
            E result = removeRoot();
            lock.notifyAll();
            return result;
        }
    }

    /** Waits for an element if the queue is empty. */
    public E take() throws InterruptedException {
        synchronized (lock) {
            checkReentrancy();
            while (size == 0) {
                lock.wait();
            }
            E result = removeRoot();
            lock.notifyAll();
            return result;
        }
    }

    /** Returns the least element, or null if the queue is empty. */
    public E peek() {
        synchronized (lock) {
            checkReentrancy();
            return size == 0 ? null : elementAt(0);
        }
    }

    public int size() {
        synchronized (lock) {
            checkReentrancy();
            return size;
        }
    }

    public int capacity() {
        synchronized (lock) {
            checkReentrancy();
            return heap.length;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            checkReentrancy();
            return size == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            checkReentrancy();
            if (size != 0) {
                for (int i = 0; i < size; i++) {
                    heap[i] = null;
                }
                size = 0;
                lock.notifyAll();
            }
        }
    }

    private void checkReentrancy() {
        if (comparing) {
            throw new IllegalStateException("queue accessed from its ordering");
        }
    }

    private int compare(E first, E second) {
        comparing = true;
        try {
            return ordering.compare(first, second);
        } finally {
            comparing = false;
        }
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) heap[index];
    }

    private void insert(E element) {
        int destination = size;
        while (destination > 0) {
            int parent = (destination - 1) / 2;
            if (compare(element, elementAt(parent)) >= 0) {
                break;
            }
            destination = parent;
        }

        int index = size;
        while (index > destination) {
            int parent = (index - 1) / 2;
            heap[index] = heap[parent];
            index = parent;
        }
        heap[destination] = element;
        size++;
    }

    private E removeRoot() {
        E result = elementAt(0);
        int newSize = size - 1;
        if (newSize == 0) {
            heap[0] = null;
            size = 0;
            return result;
        }

        E last = elementAt(newSize);
        int[] path = new int[31];
        int pathLength = 0;
        int index = 0;

        // Determine the path before changing the heap, so a failed comparison
        // leaves the queue unchanged.
        while (index < newSize / 2) {
            int child = index * 2 + 1;
            int right = child + 1;
            if (right < newSize && compare(elementAt(right), elementAt(child)) < 0) {
                child = right;
            }
            if (compare(last, elementAt(child)) <= 0) {
                break;
            }
            path[pathLength++] = child;
            index = child;
        }

        index = 0;
        for (int i = 0; i < pathLength; i++) {
            int child = path[i];
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = last;
        heap[newSize] = null;
        size = newSize;
        return result;
    }
}
