@DelayQueue.ThreadSafe
public class DelayQueue<E extends DelayQueue.Delayed> {
    @interface ThreadSafe {}

    public interface Delayed {
        long getDelay();
    }

    private static final class Entry<E extends Delayed> {
        final E item;
        final long deadlineNanos;

        Entry(E item, long deadlineNanos) {
            this.item = item;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private final Object monitor;
    private final int capacity;
    private Entry<E>[] heap;
    private int size;

    public DelayQueue() {
        this(Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.monitor = new Object();
        this.heap = (Entry<E>[]) new Entry[16];
        this.size = 0;
    }

    public boolean offer(E item) {
        if (item == null) {
            throw new NullPointerException("item is null");
        }
        synchronized (monitor) {
            if (size >= capacity) {
                return false;
            }
            insert(item);
            monitor.notifyAll();
            return true;
        }
    }

    public void put(E item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item is null");
        }
        synchronized (monitor) {
            while (size >= capacity) {
                monitor.wait();
            }
            insert(item);
            monitor.notifyAll();
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0 || heap[0].deadlineNanos > System.nanoTime()) {
                if (size == 0) {
                    monitor.wait();
                } else {
                    long waitNanos = heap[0].deadlineNanos - System.nanoTime();
                    if (waitNanos <= 0L) {
                        continue;
                    }
                    long waitMillis = Math.max(1L, waitNanos / 1_000_000L);
                    monitor.wait(waitMillis);
                }
            }
            E item = removeHead();
            monitor.notifyAll();
            return item;
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            if (heap[0].deadlineNanos > System.nanoTime()) {
                return null;
            }
            E item = removeHead();
            monitor.notifyAll();
            return item;
        }
    }

    public E poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0");
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;

        synchronized (monitor) {
            while (true) {
                if (size > 0 && heap[0].deadlineNanos <= System.nanoTime()) {
                    E item = removeHead();
                    monitor.notifyAll();
                    return item;
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return null;
                }

                if (size == 0) {
                    monitor.wait(remaining);
                } else {
                    long delayNanos = heap[0].deadlineNanos - System.nanoTime();
                    long waitMillis = Math.min(remaining, Math.max(1L, delayNanos / 1_000_000L));
                    monitor.wait(waitMillis);
                }
            }
        }
    }

    public E peek() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            return heap[0].item;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            monitor.notifyAll();
        }
    }

    public boolean add(E item) {
        if (!offer(item)) {
            throw new IllegalStateException("queue full");
        }
        return true;
    }

    public boolean remove(E item) {
        if (item == null) {
            return false;
        }

        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                if (heap[i].item.equals(item)) {
                    removeAt(i);
                    monitor.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    private void insert(E item) {
        long delayNanos = item.getDelay() * 1_000_000L;
        long deadlineNanos = System.nanoTime() + delayNanos;

        ensureCapacity(size + 1);
        Entry<E> node = new Entry<E>(item, deadlineNanos);
        heap[size] = node;
        siftUp(size);
        size++;
    }

    private E removeHead() {
        Entry<E> target = heap[0];
        int lastIndex = --size;
        heap[0] = heap[lastIndex];
        heap[lastIndex] = null;

        if (size > 0) {
            siftDown(0);
        }

        return target.item;
    }

    private void removeAt(int index) {
        int lastIndex = --size;
        if (index == lastIndex) {
            heap[index] = null;
            return;
        }

        Entry<E> moved = heap[lastIndex];
        heap[lastIndex] = null;
        heap[index] = moved;

        siftDown(index);
        if (heap[index] == moved) {
            siftUp(index);
        }
    }

    private void siftUp(int index) {
        while (index > 0) {
            int parentIndex = (index - 1) / 2;
            if (heap[parentIndex].deadlineNanos <= heap[index].deadlineNanos) {
                return;
            }
            swap(parentIndex, index);
            index = parentIndex;
        }
    }

    private void siftDown(int index) {
        int left = index * 2 + 1;

        while (left < size) {
            int right = left + 1;
            int smallest = left;

            if (right < size && heap[right].deadlineNanos < heap[left].deadlineNanos) {
                smallest = right;
            }

            if (heap[index].deadlineNanos <= heap[smallest].deadlineNanos) {
                return;
            }

            swap(index, smallest);
            index = smallest;
            left = index * 2 + 1;
        }
    }

    private void swap(int i, int j) {
        Entry<E> temp = heap[i];
        heap[i] = heap[j];
        heap[j] = temp;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int required) {
        if (required <= heap.length) {
            return;
        }

        int newCapacity = heap.length << 1;
        while (newCapacity < required) {
            newCapacity <<= 1;
        }

        Entry<E>[] newHeap = (Entry<E>[]) new Entry[newCapacity];
        System.arraycopy(heap, 0, newHeap, 0, heap.length);
        heap = newHeap;
    }
}
