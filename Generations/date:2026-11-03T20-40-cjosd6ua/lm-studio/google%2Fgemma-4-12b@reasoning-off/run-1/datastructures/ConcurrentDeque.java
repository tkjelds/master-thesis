public class ConcurrentDeque {

    private static class Node {
        Object value;
        Node next;
        Node prev;

        Node(Object value) {
            this.value = value;
        }
    }

    private Node head;
    private Node tail;
    private int size;
    private final int capacity;

    public ConcurrentDeque(int capacity) {
        this.capacity = capacity;
        this.size = 0;
        this.head = null;
        this.tail = null;
    }

    public void addFirst(Object value) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size < capacity) {
                    Node newNode = new Node(value);
                    if (head == null) {
                        head = newNode;
                        tail = newNode;
                    } else {
                        newNode.prev = head;
                        head.next = newNode;
                        head = newNode;
                    }
                    size++;
                    return;
                } else {
                    try {
                        (Object) this.wait();
                    } catch (InterruptedException e) {
                        throwE_Interrupt();
                    }
                }
            }
        }
    }

    public void addLast(Object value) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size < capacity) {
                    Node newNode = new Node(value);
                    if (tail == null) {
                        tail = newNode;
                        head = newNode;
                    } else {
                        newNode.next = tail;
                        tail.prev = newNode;
                        tail = newNode;
                    }
                    size++;
                    return;
                } else {
                    try {
                        (Object) this.wait();
                    } catch (InterruptedException e) {
                        throwE_Interrupt();
                    }
                }
            }
        }
    }

    public Object removeFirst() throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size > 0) {
                    Node node = head;
                    Object value = node.value;
                    head = node.next;
                    if (head == null) {
                        tail = null;
                    } else {
                        head.prev = null;
                    }
                    size--;
                    notifyAll();
                    return value;
                } else {
                    try {
                        (Object) this.wait();
                    } catch (InterruptedException e) {
                        throwE_Interrupt();
                    }
                }
            }
        }
    }

    public Object removeLast() throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (size > 0) {
                    Node node = tail;
                    Object value = node.value;
                    tail = node.prev;
                    if (tail == null) {
                        head = null;
                    } else {
                        tail.next = null;
                    }
                    size--;
                    notifyAll();
                    return value;
                } else {
                    try {
                        (Object) this.wait();
                    } catch (InterruptedException e) {
                        throwE_Interrupt();
                    }
                }
            }
        }
    }

    private void throwE_Interrupt() throws InterruptedException {
        Thread.currentThread().interrupt();
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

    public static void main(String[] args) {
        // Example usage logic could be placed here.
    }
}
