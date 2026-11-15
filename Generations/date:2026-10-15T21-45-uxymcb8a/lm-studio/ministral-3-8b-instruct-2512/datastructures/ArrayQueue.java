@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue() {
        this(DEFAULT_CAPACITY);
    }

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.elements = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    public void enqueue(Object item) {
        synchronized (this) {
            while (size == elements.length) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            elements[tail] = item;
            tail = (tail + 1) % elements.length;
            size++;
            notifyAll();
        }
    }

    public Object dequeue() {
        synchronized (this) {
            while (size == 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            Object item = elements[head];
            head = (head + 1) % elements.length;
            size--;
            notifyAll();
            return item;
        }
    }

    public Object peek() {
        synchronized (this) {
            while (size == 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            Object item = elements[head];
            return item;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public void clear() {
        synchronized (this) {
            head = 0;
            tail = 0;
            size = 0;
        }
    }

    public boolean contains(Object item) {
        synchronized (this) {
            if (size == 0) return false;

            int current = head;
            for (int i = 0; i < size; i++) {
                if (elements[current].equals(item)) {
                    return true;
                }
                current = (current + 1) % elements.length;
            }

            return false;
        }
    }
}
