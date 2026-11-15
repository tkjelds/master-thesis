@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}


    // Bounded blocking FIFO queue backed by a circular array.
    // Policy: put/enqueue blocks while full; take/dequeue blocks while empty.
    // Non-blocking variants (offer/poll) return false/null immediately.
    // Timed variants wait using wait(timeout) in a loop with a deadline.

    private final Object lock = new Object();
    private final Object[] items;
    private int head = 0;
    private int tail = 0;
    private int count = 0;

    public ConcurrentQueue() {
        this(1024);
    }

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.items = new Object[capacity];
    }

    public void enqueue(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            insert(item);
            lock.notifyAll();
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T item = remove();
            lock.notifyAll();
            return item;
        }
    }

    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            insert(item);
            lock.notifyAll();
            return true;
        }
    }

    public T poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T item = remove();
            lock.notifyAll();
            return item;
        }
    }

    public boolean offer(T item, long timeoutMillis) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item");
        }
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        synchronized (lock) {
            while (count == items.length) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                long ms = remainingNanos / 1000000L;
                int ns = (int) (remainingNanos % 1000000L);
                if (ms == 0 && ns == 0) {
                    ns = 1;
                }
                lock.wait(ms, ns);
            }
            insert(item);
            lock.notifyAll();
            return true;
        }
    }

    public T poll(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        synchronized (lock) {
            while (count == 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return null;
                }
                long ms = remainingNanos / 1000000L;
                int ns = (int) (remainingNanos % 1000000L);
                if (ms == 0 && ns == 0) {
                    ns = 1;
                }
                lock.wait(ms, ns);
            }
            T item = remove();
            lock.notifyAll();
            return item;
        }
    }

    public T peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            T item = (T) items[head];
            return item;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public int capacity() {
        return items.length;
    }

    // Must be called while holding lock and when not full.
    private void insert(T item) {
        items[tail] = item;
        tail = (tail + 1) % items.length;
        count++;
    }

    // Must be called while holding lock and when not empty.
    @SuppressWarnings("unchecked")
    private T remove() {
        T item = (T) items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return item;
    }

    public static void main(String[] args) throws InterruptedException {
        final ConcurrentQueue<Integer> q = new ConcurrentQueue<Integer>(8);
        final int producers = 4;
        final int perProducer = 1000;
        final long[] sum = new long[1];
        final Object sumLock = new Object();

        Thread[] ps = new Thread[producers];
        Thread[] cs = new Thread[producers];
        for (int i = 0; i < producers; i++) {
            ps[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        for (int j = 1; j <= perProducer; j++) {
                            q.enqueue(j);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            cs[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        long local = 0;
                        for (int j = 0; j < perProducer; j++) {
                            local += q.dequeue();
                        }
                        synchronized (sumLock) {
                            sum[0] += local;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            ps[i].start();
            cs[i].start();
        }
        for (int i = 0; i < producers; i++) {
            ps[i].join();
            cs[i].join();
        }
        long expected = (long) producers * perProducer * (perProducer + 1) / 2;
        System.out.println("sum=" + sum[0] + " expected=" + expected);
    }
}
