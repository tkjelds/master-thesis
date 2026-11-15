/**
 * A bounded, thread-safe min-priority queue using natural ordering.
 *
 * Null elements are not permitted. Elements must retain a consistent natural
 * ordering while in the queue, and comparisons must not modify this queue.
 *
 * put and take wait for space or an element, respectively, and are interruptible.
 * offer and poll return immediately when the queue is full or empty.
 */
@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] heap;
    private int size;

    public ConcurrentPriorityQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        heap = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == heap.length) {
                lock.wait();
            }
            insert(element);
            lock.notifyAll();
        }
    }

    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == heap.length) {
                return false;
            }
            insert(element);
            lock.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = removeMinimum();
            lock.notifyAll();
            return result;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = removeMinimum();
            lock.notifyAll();
            return result;
        }
    }

    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(0);
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

    public int capacity() {
        return heap.length;
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("element");
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
            if (element.compareTo(elementAt(parent)) >= 0) {
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

    private E removeMinimum() {
        E result = elementAt(0);
        int newSize = size - 1;
        if (newSize == 0) {
            heap[0] = null;
            size = 0;
            return result;
        }

        E last = elementAt(newSize);
        int[] path = new int[32];
        int pathLength = 0;
        int index = 0;

        while (index < newSize / 2) {
            int child = index * 2 + 1;
            int right = child + 1;
            if (right < newSize && elementAt(right).compareTo(elementAt(child)) < 0) {
                child = right;
            }
            if (last.compareTo(elementAt(child)) <= 0) {
                break;
            }
            path[pathLength++] = child;
            index = child;
        }

        index = 0;
        for (int i = 0; i < pathLength; i++) {
            heap[index] = heap[path[i]];
            index = path[i];
        }
        heap[index] = last;
        heap[newSize] = null;
        size = newSize;
        return result;
    }
}
