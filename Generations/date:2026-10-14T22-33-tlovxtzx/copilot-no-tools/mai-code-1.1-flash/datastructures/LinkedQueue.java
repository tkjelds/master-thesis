@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E item;
        private Node<E> next;

        private Node(E item) {
            this.item = item;
            this.next = null;
        }
    }

    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public LinkedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    public void put(E item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (this) {
            while (size >= capacity) {
                wait();
            }
            enqueueLocked(item);
            notifyAll();
        }
    }

    public boolean offer(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (this) {
            if (size >= capacity) {
                return false;
            }
            enqueueLocked(item);
            notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E item = dequeueLocked();
            notifyAll();
            return item;
        }
    }

    public E poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            E item = dequeueLocked();
            notifyAll();
            return item;
        }
    }

    public E peek() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return head.item;
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
            head = null;
            tail = null;
            size = 0;
            notifyAll();
        }
    }

    private void enqueueLocked(E item) {
        Node<E> node = new Node<E>(item);
        if (head == null) {
            head = node;
            tail = node;
        } else {
            tail.next = node;
            tail = node;
        }
        size++;
    }

    private E dequeueLocked() {
        E item = head.item;
        head = head.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return item;
    }
}
