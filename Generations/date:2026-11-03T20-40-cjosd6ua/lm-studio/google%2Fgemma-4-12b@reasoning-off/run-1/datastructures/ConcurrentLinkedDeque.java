public class ConcurrentLinkedDeque {

    private static class Node {
        Object value;
        Node prev;
        Node next;

        Node(Object value) {
            this.value = value;
        }
    }

    private Node head;
    private Node tail;
    private int size;
    private final int capacity;

    public ConcurrentLinkedDeque(int capacity) {
        this.capacity = capacity;
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    public void pushFront(Object value) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size < capacity) {
                    Node newNode = new Node(value);
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
                    // Wait if full
                    synchronized (this) {
                        // Inner sync block handled by outer structure's monitor
                    }
                    try {
                        synchronized (this) {
                            while (size == capacity) {
                                wait();
                            }
                        }
                    } catch (InterruptedException e) {
                        throwForInterrupt();
                    }
                }
            }
        }
    }

    // Correcting the logic for blocking: A simple blocking approach
    public void putFront(Object value) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            Node newNode = new Node(value);
            if (head == null) {
                head = newNode;
                tail = newNode;
            } else {
                newNode.next = head;
                head.prev = newNode;
                head = newNode;
            }
            size++;
            notifyAll();
        }
    }

    public void putBack(Object value) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            Node newNode = new Node(value);
            if (tail == null) {
                tail = newNode;
                head = newNode;
            } else {
                newNode.prev = tail;
                tail.next = newNode;
                tail = newNode;
            }
            size++;
            notifyAll();
        }
    }

    public Object takeFront() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Object val = head.value;
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

    public Object takeBack() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Object val = tail.value;
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

    private void throwForInterrupt() throws InterruptedException {
        throw new InterruptedException();
    }

    public static void main(String[] args) {
        // Example usage could go here if needed, 
        // but the requirement is just the implementation.
    }
}
