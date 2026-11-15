package array_queue_impl;

@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> implements ArrayQueueInterface<E> {
    @interface ThreadSafe {}


    private static final int DEFAULT_CAPACITY = 4;

    private Object[] elements;
    private int head;
    private int tail;
    private int count;
    private int capacity;

    public ArrayQueue() {
        this(DEFAULT_CAPACITY);
    }

    public ArrayQueue(int initialCapacity) {
        this.elements = new Object[initialCapacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
        this.capacity = initialCapacity;
    }

    @Override
    public synchronized boolean isEmpty() {
        return count == 0;
    }

    @Override
    public synchronized boolean isFull() {
        return count == capacity;
    }

    @Override
    public synchronized void put(E element) throws InterruptedException {
        while (count == capacity) {
            wait();
        }
        synchronized (elements) {
            int tailIndex = tail % capacity;
            elements[tailIndex] = element;
            tail = tail + 1;
            count = count + 1;
            notifyAll();
        }
    }

    @Override
    public synchronized E take() throws InterruptedException {
        while (count == 0) {
            wait();
        }
        synchronized (elements) {
            int headIndex = head % capacity;
            E item = (E) elements[headIndex];
            elements[headIndex] = null;
            head = head + 1;
            count = count - 1;
            notifyAll();
            return item;
        }
    }

    @Override
    public synchronized E poll() {
        if (count == 0) {
            return null;
        }
        synchronized (elements) {
            int headIndex = head % capacity;
            E item = (E) elements[headIndex];
            elements[headIndex] = null;
            head = head + 1;
            count = count - 1;
            // Check if there are waiting threads for put operations and notify them
            if (count < capacity) {
                notifyAll();
            }
            return item;
        }
    }

    @Override
    public synchronized E peek() {
        if (count == 0) {
            return null;
        }
        synchronized (elements) {
            int headIndex = head % capacity;
            E item = (E) elements[headIndex];
            return item;
        }
    }

    @Override
    public synchronized int size() {
        return count;
    }

    @Override
    public synchronized boolean add(E element) {
        if (count == capacity) {
            return false;
        }
        synchronized (elements) {
            int tailIndex = tail % capacity;
            elements[tailIndex] = element;
            tail = tail + 1;
            count = count + 1;
            notifyAll();
            return true;
        }
    }

    @Override
    public synchronized boolean remove() {
        if (count == 0) {
            return false;
        }
        synchronized (elements) {
            int headIndex = head % capacity;
            elements[headIndex] = null;
            head = head + 1;
            count = count - 1;
            notifyAll();
            return true;
        }
    }

    @Override
    public synchronized void clear() {
        synchronized (elements) {
            head = 0;
            tail = 0;
            count = 0;
            synchronized (this) {
                notifyAll();
            }
        }
    }

    // Inner interface definition for self-containment if needed by external code style
    // Since the prompt asks to implement the structure, we define the interface within this file
    // to ensure the code is standalone without external dependencies.
    
    public interface ArrayQueueInterface<E> {
        boolean isEmpty();
        boolean isFull();
        void put(E element) throws InterruptedException;
        E take() throws InterruptedException;
        E poll();
        E peek();
        int size();
        boolean add(E element);
        boolean remove();
        void clear();
    }
}
