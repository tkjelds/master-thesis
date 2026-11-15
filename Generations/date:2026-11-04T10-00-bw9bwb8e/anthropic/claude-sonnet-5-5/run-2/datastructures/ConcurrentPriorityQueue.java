import java.lang.Comparable;

/**
 * A thread-safe, bounded, blocking priority queue backed by a binary min-heap.
 *
 * Ordering: the element that is "smallest" according to the comparator (or natural
 * ordering) is removed first. Elements that compare as equal are removed in FIFO
 * (insertion) order.
 *
 * Policy for operations that cannot complete immediately:
 *  - put(): blocks while the queue is full.
 *  - take(): blocks while the queue is empty.
 *  - offer(): non-blocking, returns false if the queue is full.
 *  - poll(): non-blocking, returns null if the queue is empty.
 *  - peek(): non-blocking, returns null if the queue is empty.
 *
 * All state is guarded by a single intrinsic lock; waiting uses wait/notifyAll
 * inside while loops. Blocking methods propagate InterruptedException.
 */
@ConcurrentPriorityQueue.ThreadSafe
public class ConcurrentPriorityQueue<T> {
    @interface ThreadSafe {}


    /** Custom comparator interface (no library imports allowed). */
    public interface PriorityComparator<E> {
        int compare(E a, E b);
    }

    private static final class Entry {
        final Object item;
        final long seq;

        Entry(Object item, long seq) {
            this.item = item;
            this.seq = seq;
        }
    }

    private final Object lock = new Object();
    private final Entry[] heap;
    private final int capacity;
    private final PriorityComparator<? super T> comparator; // may be null -> natural ordering
    private int size = 0;
    private long nextSeq = 0;

    /** Creates a queue with natural ordering (elements must implement Comparable). */
    public ConcurrentPriorityQueue(int capacity) {
        this(capacity, null);
    }

    /** Creates a queue with the given capacity and comparator (null for natural ordering). */
    public ConcurrentPriorityQueue(int capacity, PriorityComparator<? super T> comparator) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.heap = new Entry[capacity];
        this.comparator = comparator;
    }

    /** Inserts an element, blocking while the queue is full. */
    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            insertLocked(item);
            lock.notifyAll();
        }
    }

    /** Inserts an element if space is available; returns false if full. */
    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            insertLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns the highest-priority element, blocking while empty. */
    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T result = (T) removeTopLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Removes and returns the highest-priority element, or null if empty. */
    @SuppressWarnings("unchecked")
    public T poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T result = (T) removeTopLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Returns (without removing) the highest-priority element, or null if empty. */
    @SuppressWarnings("unchecked")
    public T peek() {
        synchronized (lock) {
            return size == 0 ? null : (T) heap[0].item;
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
        return capacity;
    }

    /** Removes all elements. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    // ---- internal helpers; must be called with lock held ----

    private void insertLocked(T item) {
        Entry e = new Entry(item, nextSeq++);
        heap[size] = e;
        size++;
        siftUp(size - 1);
    }

    private Object removeTopLocked() {
        Entry top = heap[0];
        size--;
        Entry last = heap[size];
        heap[size] = null;
        if (size > 0) {
            heap[0] = last;
            siftDown(0);
        }
        return top.item;
    }

    @SuppressWarnings("unchecked")
    private boolean less(Entry a, Entry b) {
        int c;
        if (comparator != null) {
            c = ((PriorityComparator<Object>) comparator).compare(a.item, b.item);
        } else {
            c = ((Comparable<Object>) a.item).compareTo(b.item);
        }
        if (c != 0) {
            return c < 0;
        }
        return a.seq < b.seq;
    }

    private void siftUp(int index) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (less(heap[index], heap[parent])) {
                Entry tmp = heap[index];
                heap[index] = heap[parent];
                heap[parent] = tmp;
                index = parent;
            } else {
                break;
            }
        }
    }

    private void siftDown(int index) {
        while (true) {
            int left = 2 * index + 1;
            if (left >= size) {
                break;
            }
            int right = left + 1;
            int smallest = left;
            if (right < size && less(heap[right], heap[left])) {
                smallest = right;
            }
            if (less(heap[smallest], heap[index])) {
                Entry tmp = heap[index];
                heap[index] = heap[smallest];
                heap[smallest] = tmp;
                index = smallest;
            } else {
                break;
            }
        }
    }
}
