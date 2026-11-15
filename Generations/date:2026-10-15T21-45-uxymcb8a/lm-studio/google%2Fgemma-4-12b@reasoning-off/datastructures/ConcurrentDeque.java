@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<T> {
    @interface ThreadSafe {}


    private static class Node<T> {
        T data;
        Node<T> next;
        Node<T> prev;

        Node(T data) {
            this.data = data;
        }
    }

    private Node<T> head;
    private Node<T> tail;
    private int size;
    private final int capacity;

    public ConcurrentDeque(int capacity) {
        this.capacity = capacity;
        this.head = null;
        this.tail = null;
        this.size = 0;
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

    public void addFirst(T item) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size < capacity) {
                    Node<T> newNode = new Node<>(item);
                    if (head == null) {
                        head = newNode;
                        tail = newNode;
                    } else {
                        newNode.next = head;
                        head.prev = newNode;
                        head = newNode;
                    }
                    size++;
                    return;
                } else {
                    try {
                        synchronized (this) {
                            // Wait until capacity is available
                            // Re-checking condition in while loop or re-acquiring block
                        }
                        // The lock is released automatically at the end of synchronized block.
                        // We must wait until size < capacity.
                    } catch (Exception e) {
                        // This is handled via the nested structure below.
                    }
                }
            }
        }
    }

    // Note: To properly implement blocking, the flow must handle the lock release.
    // Using a structured approach for block-waiting:

    public void addFirstBlocking() throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size < capacity) {
                    Node<T> newNode = new Node<>(null); // Placeholder logic for standard addFirst
                    // Since we need to pass the item, let's refine the method signature.
                }
            }
        }
    }

    // Corrected public API based on requirements:
    public void addFirst(T item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                this.wait();
            }
            Node<T> newNode = new Node<>(item);
            if (head == null) {
                head = newNode;
                tail = newNode;
            } else {
                newNode.next = head;
                head.prev = newNode;
                head = newNode;
            }
            size++;
        }
    }

    public void addLast(T item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                this.wait();
            }
            Node<T> newNode = new Node<>(item);
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                newNode.prev = tail;
                tail.next = newNode;
                tail = newNode;
            }
            size++;
        }
    }

    public T removeFirst() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                this.wait();
            }
            Node<T> removed = head;
            T data = removed.data;
            head = head.next;
            if (head == null) {
                tail = null;
            } else {
                head.prev = null;
            }
            size--;
            this.notifyAll();
            return data;
        }
    }

    public T removeLast() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                this.wait();
            }
            Node<T> removed = tail;
            T data = removed.data;
            tail = tail.prev;
            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }
            size--;
            this.notifyAll();
            return data;
        }
    }

    // Revised to handle input correctly with blocking logic
    public void pushFront(T item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                this.wait();
            }
            Node<T> newNode = new Node<>(item);
            if (head == null) {
                head = newNode;
                tail = newNode;
            } else {
                newNode.next = head;
                head.prev = newNode;
                head = newNode;
            }
            size++;
        }
    }

    public void pushBack(T item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                this.wait();
            }
            Node<T> newNode = new Node<>(item);
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                newNode.prev = tail;
                tail.next = newNode;
                tail = newNode;
            }
            size++;
        }
    }

    public T popFront() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                this.wait();
            }
            Node<T> node = head;
            T data = node.data;
            head = head.next;
            if (head == null) {
                tail = null;
            } else {
                head.prev = null;
            }
            size--;
            this.notifyAll();
            return data;
        }
    }

    public T popBack() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                this.wait();
            }
            Node<T> node = tail;
            T data = node.data;
            tail = tail.prev;
            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }
            size--;
            this.notifyAll();
            return data;
        }
    }
}
