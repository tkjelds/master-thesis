<final_answer>
/Users/tore/master-thesis/Generations/date:2026-09-21T20-02/copilot-no-tools/gpt-5.6-luna/LinkedQueue.java:1-135
/Users/tore/master-thesis/Generations/date:2026-09-15T17-28/copilot-no-tools/gpt-5.6-luna/linked queue.java:1-87
</final_answer>
public final class LinkedQueue<T> {
    private static final class Node<E> {
        private final E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public LinkedQueue() {
        this.capacity = 0;
    }

    public LinkedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        this.capacity = capacity;
    }

    public void enqueue(T value) throws InterruptedException {
        requireValue(value);

        synchronized (lock) {
            while (capacity > 0 && size >= capacity) {
                lock.wait();
            }

            append(value);
            lock.notifyAll();
        }
    }

    public boolean offer(T value) {
        requireValue(value);

        synchronized (lock) {
            if (capacity > 0 && size >= capacity) {
                return false;
            }

            append(value);
            lock.notifyAll();
            return true;
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }

            T value = removeFirst();
            lock.notifyAll();
            return value;
        }
    }

    public T poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

            T value = removeFirst();
            lock.notifyAll();
            return value;
        }
    }

    public T peek() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }

            return head.value;
        }
    }

    public T peekImmediate() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return capacity > 0 && size >= capacity;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        synchronized (lock) {
            return capacity;
        }
    }

    private void append(T value) {
        Node<T> node = new Node<T>(value);

        if (tail == null) {
            head = node;
            tail = node;
        } else {
            tail.next = node;
            tail = node;
        }

        size++;
    }

    private T removeFirst() {
        Node<T> node = head;
        head = node.next;
        node.next = null;
        size--;

        if (head == null) {
            tail = null;
        }

        return node.value;
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }
    }
}
