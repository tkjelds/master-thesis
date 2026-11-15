@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private final Object[] items;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private final int capacity;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero.");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    /**
     * Adds an item to the rear of the queue. 
     * Blocks if the queue is full.
     */
    public void enqueue(T item) {
        if (item == null) {
            throw new IllegalArgumentException("Cannot enqueue a null item.");
        }
        synchronized (lock) {
            while (size == capacity) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            items[tail] = item;
            tail = (tail + 1) % capacity;
            size++;

            // Notify all waiting threads (could be producers or consumers)
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the item from the front of the queue.
     * Blocks if the queue is empty.
     */
    @SuppressWarnings("unchecked")
    public T dequeue() {
        synchronized (lock) {
            while (size == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            T item = (T) items[head];
            items[head] = null; // Clear for garbage collection
            head = (head + 1) % capacity;
            size--;

            // Notify all waiting threads (could be producers or consumers)
            lock.notifyAll();
            return item;
        }
    }

    private final Object lock = new Object();

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int getCapacity() {
        return capacity;
    }
}

/**
 * Test class to demonstrate the thread-safe queue.
 */
@QueueDemo.ThreadSafe
public class QueueDemo {
    @interface ThreadSafe {}

    public static void main(String[] args) {
        // A queue with a capacity of 5
        ConcurrentQueue<Integer> queue = new ConcurrentQueue<>(5);

        // Producer threads
        Runnable producer = new Runnable() {
            public void run() {
                for (int i = 0; i < 10; i++) {
                    queue.enqueue(i);
                }
            }
        };

        // Consumer threads
        Runnable consumer = new Runnable() {
            public void run() {
                for (int i = 0; i < 10; i++) {
                    Integer val = queue.dequeue();
                    if (val != null) {
                        // Successfully dequeued
                    }
                }
            }
        };

        Thread t1 = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 10; i++) {
                    queue.enqueue(i);
                }
            }
        });

        Thread t2 = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 10; i++) {
                    queue.enqueue(i);
                }
            }
        });

        Thread c1 = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 10; i++) {
                    queue.dequeue();
                }
            }
        });

        Thread c2 = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 10; i++) {
                    queue.dequeue();
                }
            }
        });

        t1.start();
        t2.start();
        c1.start();
        c2.start();

        try {
            t1.join();
            t2.join();
            c1.join();
            c2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Finished testing concurrent operations.");
    }
}
