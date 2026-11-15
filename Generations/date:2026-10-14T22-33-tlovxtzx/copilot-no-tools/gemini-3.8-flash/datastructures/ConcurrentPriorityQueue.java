@ConcurrentPriorityQueue.ThreadSafe
public class ConcurrentPriorityQueue<E> {
    @interface ThreadSafe {}


    public interface PriorityComparator<T> {
        int compare(T first, T second);
    }

    private final Object lock = new Object();
    private final Object[] heap;
    private final int capacity;
    private final PriorityComparator<? super E> comparator;
    private int size;

    public ConcurrentPriorityQueue(int capacity) {
        this(capacity, null);
    }

    public ConcurrentPriorityQueue(int capacity, PriorityComparator<? super E> comparator) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        this.capacity = capacity;
        this.heap = new Object[capacity];
        this.comparator = comparator;
        this.size = 0;
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            enqueue(element);
            lock.notifyAll();
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = dequeue();
            lock.notifyAll();
            return item;
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            enqueue(element);
            lock.notifyAll();
            return true;
        }
    }

    public boolean offer(E element, long timeoutMillis) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        synchronized (lock) {
            long remainingNanos = timeoutMillis * 1_000_000L;
            long deadline = System.nanoTime() + remainingNanos;
            while (size == capacity) {
                if (remainingNanos <= 0) {
                    return false;
                }
                long millis = (remainingNanos + 999_999L) / 1_000_000L;
                lock.wait(millis);
                remainingNanos = deadline - System.nanoTime();
            }
            enqueue(element);
            lock.notifyAll();
            return true;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E item = dequeue();
            lock.notifyAll();
            return item;
        }
    }

    public E poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        synchronized (lock) {
            long remainingNanos = timeoutMillis * 1_000_000L;
            long deadline = System.nanoTime() + remainingNanos;
            while (size == 0) {
                if (remainingNanos <= 0) {
                    return null;
                }
                long millis = (remainingNanos + 999_999L) / 1_000_000L;
                lock.wait(millis);
                remainingNanos = deadline - System.nanoTime();
            }
            E item = dequeue();
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (E) heap[0];
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
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

    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            return indexOf(element) != -1;
        }
    }

    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            int index = indexOf(element);
            if (index == -1) {
                return false;
            }
            removeAt(index);
            lock.notifyAll();
            return true;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = heap[i];
            }
            return copy;
        }
    }

    private void enqueue(E element) {
        int i = size;
        siftUp(i, element);
        size = i + 1;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        int lastIndex = size - 1;
        E result = (E) heap[0];
        Object moved = heap[lastIndex];
        heap[lastIndex] = null;
        size = lastIndex;
        if (lastIndex > 0) {
            siftDown(0, moved);
        }
        return result;
    }

    private void removeAt(int index) {
        int lastIndex = size - 1;
        if (lastIndex == index) {
            heap[index] = null;
            size = lastIndex;
        } else {
            Object moved = heap[lastIndex];
            heap[lastIndex] = null;
            size = lastIndex;
            siftDown(index, moved);
            if (heap[index] == moved) {
                siftUp(index, moved);
            }
        }
    }

    private int indexOf(Object element) {
        for (int i = 0; i < size; i++) {
            if (element.equals(heap[i])) {
                return i;
            }
        }
        return -1;
    }

    private void siftUp(int index, Object element) {
        int k = index;
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object parentElement = heap[parent];
            if (compare(element, parentElement) >= 0) {
                break;
            }
            heap[k] = parentElement;
            k = parent;
        }
        heap[k] = element;
    }

    private void siftDown(int index, Object element) {
        int k = index;
        int half = size >>> 1;
        while (k < half) {
            int child = (k << 1) + 1;
            Object childElement = heap[child];
            int right = child + 1;
            if (right < size && compare(childElement, heap[right]) > 0) {
                child = right;
                childElement = heap[right];
            }
            if (compare(element, childElement) <= 0) {
                break;
            }
            heap[k] = childElement;
            k = child;
        }
        heap[k] = element;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compare(Object a, Object b) {
        if (comparator != null) {
            return comparator.compare((E) a, (E) b);
        }
        return ((Comparable) a).compareTo(b);
    }
}
