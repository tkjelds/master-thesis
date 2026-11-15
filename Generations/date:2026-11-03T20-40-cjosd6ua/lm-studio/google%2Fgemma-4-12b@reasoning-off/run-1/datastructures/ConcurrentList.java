public class ConcurrentList {

    private Object[] elements;
    private int size;
    private final int capacity;
    private final Object lock = new Object();

    public ConcurrentList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.size = 0;
    }

    public void add(Object item) throws InterruptedException {
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            elements[size] = item;
            size++;
            lock.notifyAll();
        }
    }

    public Object remove() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Object item = elements[size - 1];
            elements[size - 1] = null;
            size--;
            lock.notifyAll();
            return item;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean contains(Object item) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (elements[i] == item) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public static void main(String[] args) {
        // Example usage structure
        ConcurrentList list = new ConcurrentList(5);

        Thread producer = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < 10; i++) {
                        list.add(new Object());
                        System.out.println("Added item");
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
                        list.remove();
                        System.out.println("Removed item");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        producer.start();
        consumer.start();
    }
}
