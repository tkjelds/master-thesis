public class ArrayQueue {

    private final Object[] items;
    private int putPtr;
    private int takePtr;
    private int count;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.items = new Object[capacity];
        this.putPtr = 0;
        this.takePtr = 0;
        this.count = 0;
    }

    public void put(Object element) throws InterruptedException {
        synchronized (this) {
            while (count == items.length) {
                (this).wait();
            }

            items[putPtr] = element;
            putPtr = (putPtr + 1) % items.length;
            count++;

            (this).notifyAll();
        }
    }

    public Object take() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                (this).wait();
            }

            Object element = items[takePtr];
            items[takePtr] = null; // Clear reference for GC
            takePtr = (takePtr + 1) % items.length;
            count--;

            (this).notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return count == items.length;
        }
    }

    public static void main(String[] args) {
        // Simple demonstration of the queue logic.
        ArrayQueue queue = new ArrayQueue(5);

        Thread producer = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < 10; i++) {
                        queue.put(new Object());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread consumer = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < 10; i++) {
                        queue.take();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        producer.start();
        consumer.start();

        try {
            producer.join();
            consumer.join();
            System.out.println("Finished successfully.");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
