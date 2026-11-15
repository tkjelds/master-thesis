interface Delayed extends DelayQueue.Delayed {}

@DelayQueue.ThreadSafe
public class DelayQueue<E> {
    @interface ThreadSafe {}


    public interface Delayed {
        default long getDelayMillis() {
            return getDelay();
        }

        default long getDelay() {
            return 0L;
        }

        default long getDelayNanos() {
            return getDelayMillis() * 1000000L;
        }
    }

    private static final class Entry<T> {
        final T item;
        final long deadlineNanos;
        final long sequenceNumber;

        Entry(T item, long deadlineNanos, long sequenceNumber) {
            this.item = item;
            this.deadlineNanos = deadlineNanos;
            this.sequenceNumber = sequenceNumber;
        }
    }

    private final int capacity;
    private volatile Object[] heap;
    private volatile int size;
    private volatile long sequence;

    public DelayQueue() {
        this(Integer.MAX_VALUE);
    }

    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        int initialCapacity = capacity < 16 ? capacity : 16;
        this.heap = new Object[initialCapacity];
        this.size = 0;
        this.sequence = 0L;
    }

    public boolean offer(E item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        long delayMillis = 0L;
        if (item instanceof Delayed) {
            delayMillis = ((Delayed) item).getDelayMillis();
        }
        return offer(item, delayMillis);
    }

    public boolean offer(E item, long delayMillis) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        synchronized (this) {
            if (size >= capacity) {
                return false;
            }
            enqueue(item, delayMillis);
            notifyAll();
            return true;
        }
    }

    public boolean offer(E item, long delayMillis, long timeoutMillis) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        synchronized (this) {
            if (size < capacity) {
                enqueue(item, delayMillis);
                notifyAll();
                return true;
            }
            if (timeoutMillis == 0L) {
                return false;
            }
            long waitNanos = timeoutMillis > (Long.MAX_VALUE / 1000000L)
                    ? Long.MAX_VALUE / 2L
                    : timeoutMillis * 1000000L;
            long deadlineNanos = System.nanoTime() + waitNanos;
            while (size >= capacity) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                long millis = remainingNanos / 1000000L;
                int nanos = (int) (remainingNanos % 1000000L);
                if (millis == 0L && nanos == 0) {
                    return false;
                }
                if (millis == 0L) {
                    millis = 1L;
                    nanos = 0;
                }
                wait(millis, nanos);
            }
            enqueue(item, delayMillis);
            notifyAll();
            return true;
        }
    }

    public boolean add(E item) {
        if (offer(item)) {
            return true;
        }
        throw new IllegalStateException("Queue is full");
    }

    public boolean add(E item, long delayMillis) {
        if (offer(item, delayMillis)) {
            return true;
        }
        throw new IllegalStateException("Queue is full");
    }

    public void put(E item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        long delayMillis = 0L;
        if (item instanceof Delayed) {
            delayMillis = ((Delayed) item).getDelayMillis();
        }
        put(item, delayMillis);
    }

    public void put(E item, long delayMillis) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        synchronized (this) {
            while (size >= capacity) {
                wait();
            }
            enqueue(item, delayMillis);
            notifyAll();
        }
    }

    public E take() throws InterruptedException {
        synchronized (this) {
            while (true) {
                while (size == 0) {
                    wait();
                }
                Entry<E> head = entryAt(0);
                long remainingNanos = head.deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    E item = removeAt(0);
                    notifyAll();
                    return item;
                }
                long millis = remainingNanos / 1000000L;
                int nanos = (int) (remainingNanos % 1000000L);
                if (millis == 0L && nanos == 0) {
                    E item = removeAt(0);
                    notifyAll();
                    return item;
                }
                if (millis == 0L) {
                    millis = 1L;
                    nanos = 0;
                }
                wait(millis, nanos);
            }
        }
    }

    public E poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            Entry<E> head = entryAt(0);
            if (head.deadlineNanos - System.nanoTime() > 0L) {
                return null;
            }
            E item = removeAt(0);
            notifyAll();
            return item;
        }
    }

    public E poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        synchronized (this) {
            long waitNanos = timeoutMillis > (Long.MAX_VALUE / 1000000L)
                    ? Long.MAX_VALUE / 2L
                    : timeoutMillis * 1000000L;
            long deadlineNanos = System.nanoTime() + waitNanos;
            while (true) {
                long remainingTimeoutNanos = deadlineNanos - System.nanoTime();
                if (remainingTimeoutNanos <= 0L && (size == 0 || entryAt(0).deadlineNanos - System.nanoTime() > 0L)) {
                    return null;
                }

                if (size > 0) {
                    Entry<E> head = entryAt(0);
                    long delayNanos = head.deadlineNanos - System.nanoTime();
                    if (delayNanos <= 0L) {
                        E item = removeAt(0);
                        notifyAll();
                        return item;
                    }
                    if (remainingTimeoutNanos <= 0L) {
                        return null;
                    }
                    long targetWaitNanos = delayNanos < remainingTimeoutNanos ? delayNanos : remainingTimeoutNanos;
                    long millis = targetWaitNanos / 1000000L;
                    int nanos = (int) (targetWaitNanos % 1000000L);
                    if (millis == 0L && nanos == 0) {
                        continue;
                    }
                    if (millis == 0L) {
                        millis = 1L;
                        nanos = 0;
                    }
                    wait(millis, nanos);
                } else {
                    if (remainingTimeoutNanos <= 0L) {
                        return null;
                    }
                    long millis = remainingTimeoutNanos / 1000000L;
                    int nanos = (int) (remainingTimeoutNanos % 1000000L);
                    if (millis == 0L && nanos == 0) {
                        return null;
                    }
                    if (millis == 0L) {
                        millis = 1L;
                        nanos = 0;
                    }
                    wait(millis, nanos);
                }
            }
        }
    }

    public E peek() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return entryAt(0).item;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public int remainingCapacity() {
        synchronized (this) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            notifyAll();
        }
    }

    public boolean remove(Object target) {
        if (target == null) {
            return false;
        }
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (target.equals(entryAt(i).item)) {
                    removeAt(i);
                    notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object target) {
        if (target == null) {
            return false;
        }
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (target.equals(entryAt(i).item)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void enqueue(E item, long delayMillis) {
        ensureCapacity(size + 1);
        long now = System.nanoTime();
        long delayNanos;
        if (delayMillis <= 0L) {
            delayNanos = 0L;
        } else if (delayMillis > (Long.MAX_VALUE / 1000000L)) {
            delayNanos = Long.MAX_VALUE / 2L;
        } else {
            delayNanos = delayMillis * 1000000L;
        }
        long deadlineNanos = now + delayNanos;
        Entry<E> entry = new Entry<E>(item, deadlineNanos, sequence++);
        siftUp(size, entry);
        size++;
    }

    private void siftUp(int index, Entry<E> entry) {
        while (index > 0) {
            int parentIndex = (index - 1) >>> 1;
            Entry<E> parent = entryAt(parentIndex);
            if (!isEarlier(entry, parent)) {
                break;
            }
            heap[index] = parent;
            index = parentIndex;
        }
        heap[index] = entry;
    }

    private void siftDown(int index, Entry<E> entry) {
        int half = size >>> 1;
        while (index < half) {
            int left = (index << 1) + 1;
            Entry<E> smallest = entryAt(left);
            int right = left + 1;
            if (right < size && isEarlier(entryAt(right), smallest)) {
                smallest = entryAt(right);
                left = right;
            }
            if (!isEarlier(smallest, entry)) {
                break;
            }
            heap[index] = smallest;
            index = left;
        }
        heap[index] = entry;
    }

    private E removeAt(int index) {
        E item = entryAt(index).item;
        int lastIndex = --size;
        Entry<E> last = entryAt(lastIndex);
        heap[lastIndex] = null;
        if (index != lastIndex) {
            siftDown(index, last);
            if (heap[index] == last) {
                siftUp(index, last);
            }
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private Entry<E> entryAt(int index) {
        return (Entry<E>) heap[index];
    }

    private boolean isEarlier(Entry<E> a, Entry<E> b) {
        long diff = a.deadlineNanos - b.deadlineNanos;
        if (diff < 0L) {
            return true;
        }
        if (diff > 0L) {
            return false;
        }
        return a.sequenceNumber < b.sequenceNumber;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity <= heap.length) {
            return;
        }
        int oldCapacity = heap.length;
        int newCapacity = oldCapacity < 64 ? (oldCapacity + 2) * 2 : oldCapacity + (oldCapacity >> 1);
        if (newCapacity < minCapacity) {
            newCapacity = minCapacity;
        }
        if (newCapacity > capacity) {
            newCapacity = capacity;
        }
        Object[] newHeap = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            newHeap[i] = heap[i];
        }
        heap = newHeap;
    }
}

@ConcurrentDelayQueue.ThreadSafe
class ConcurrentDelayQueue<E> extends DelayQueue<E> {
    @interface ThreadSafe {}

    public ConcurrentDelayQueue() {
        super();
    }

    public ConcurrentDelayQueue(int capacity) {
        super(capacity);
    }
}
