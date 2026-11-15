@LinkedQueue.ThreadSafe
public class LinkedQueue<T> {
    @interface ThreadSafe {}


    private static final class Node<E> {
        private final E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
            this.next = null;
        }
    }

    private final int capacity;
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public LinkedQueue() {
        this(Integer.MAX_VALUE);
    }

    public LinkedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        this.capacity = capacity;
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    public void put(T value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (this) {
            while (size >= capacity) {
                wait();
            }
            Node<T> node = new Node<T>(value);
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            size++;
            notifyAll();
        }
    }

    public void enqueue(T value) throws InterruptedException {
        put(value);
    }

    public boolean offer(T value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (this) {
            if (size >= capacity) {
                return false;
            }
            Node<T> node = new Node<T>(value);
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            size++;
            notifyAll();
            return true;
        }
    }

    public T take() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Node<T> node = head;
            T value = node.value;
            head = node.next;
            node.next = null;
            if (head == null) {
                tail = null;
            }
            size--;
            notifyAll();
            return value;
        }
    }

    public T dequeue() throws InterruptedException {
        return take();
    }

    public T poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            Node<T> node = head;
            T value = node.value;
            head = node.next;
            node.next = null;
            if (head == null) {
                tail = null;
            }
            size--;
            notifyAll();
            return value;
        }
    }

    public T peek() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return head.value;
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

    public boolean isFull() {
        synchronized (this) {
            return size >= capacity;
        }
    }

    public int capacity() {
        synchronized (this) {
            return capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (this) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (this) {
            Node<T> current = head;
            while (current != null) {
                Node<T> next = current.next;
                current.next = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
            notifyAll();
        }
    }
}
