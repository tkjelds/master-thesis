@LinkedDeque.ThreadSafe
public class LinkedDeque<T> {
    @interface ThreadSafe {}

    private Node head;
    private Node tail;
    private int size;
    private final int capacity;

    private class Node {
        T value;
        Node prev;
        Node next;

        Node(T val) {
            this.value = val;
        }
    }

    public LinkedDeque(int capacity) {
        this.capacity = capacity;
    }

    public void addFirst(T item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            Node newNode = new Node(item);
            if (head == null) {
                head = tail = newNode;
            } else {
                newNode.next = head;
                head.prev = newNode;
                head = newNode;
            }
            size++;
            notifyAll();
        }
    }

    public void addLast(T item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            Node newNode = new Node(item);
            if (tail == null) {
                head = tail = newNode;
            } else {
                newNode.prev = tail;
                tail.next = newNode;
                tail = newNode;
            }
            size++;
            notifyAll();
        }
    }

    public T removeFirst() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Node node = head;
            T val = node.value;
            head = head.next;
            if (head == null) {
                tail = null;
            } else {
                head.prev = null;
            }
            size--;
            notifyAll();
            return val;
        }
    }

    public T removeLast() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Node node = tail;
            T val = node.value;
            tail = tail.prev;
            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }
            size--;
            notifyAll();
            return val;
        }
    }

    public T peekFirst() {
        synchronized (this) {
            if (head == null) {
                return null;
            }
            return head.value;
        }
    }

    public T peekLast() {
        synchronized (this) {
            if (tail == null) {
                return null;
            }
            return tail.value;
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
            return size == capacity;
        }
    }
}
