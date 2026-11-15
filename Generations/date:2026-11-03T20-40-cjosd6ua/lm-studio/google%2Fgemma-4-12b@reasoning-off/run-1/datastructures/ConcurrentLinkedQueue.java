public class ConcurrentLinkedQueue<T> {

    private class Node {
        T data;
        Node next;

        Node(T data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public ConcurrentLinkedQueue() {
        head = null;
        tail = null;
        size = 0;
    }

    public void enqueue(T item) {
        if (item == null) {
            throw new NullPointerException("Cannot enqueue null");
        }

        Node newNode = new Node(item);
        synchronized (this) {
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            size++;
            <audio|>.notifyAll();
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                wait();
            }

            Node nodeToRemove = head;
            head = nodeToRemove.next;
            if (head == null) {
                tail = null;
            }
            
            size--;
            return nodeToRemove.data;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head == null;
        }
    }

    private final Object lock = new Object();
    // The logic above uses 'this' as monitor. Replacing for clarity on requirements:
}

// Re-writing to ensure strict adherence to "No synchronized modifiers" and specific structure.

class ConcurrentLinkedQueueImpl<T> {
    private class Node {
        T data;
        Node next;

        Node(T data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public ConcurrentLinkedQueueImpl() {
        head = null;
        tail = null;
        size = 0;
    }

    public void enqueue(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        Node newNode = new Node(item);
        synchronized (this) {
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            size++;
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                wait();
            }
            Node nodeToRemove = head;
            head = nodeToRemove.next;
            if (head == null) {
                tail = null;
            }
            size--;
            return nodeToRemove.data;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head == null;
        }
    }
}

// Since the user requested a single file and specific class names, 
// I will provide the final version in the required structure.

class LinkedQueueTask {
    public static void main(String[] args) {
        // Main method for structure compliance if needed
    }
}

class ConcurrentLinkedQueue<T> {
    private class Node {
        T data;
        Node next;

        Node(T data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public ConcurrentLinkedQueue() {
        head = null;
        tail = null;
        size = 0;
    }

    public void enqueue(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        Node newNode = new Node(item);
        synchronized (this) {
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            size++;
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                wait();
            }
            Node nodeToRemove = head;
            head = nodeToRemove.next;
            if (head == null) {
                tail = null;
            }
            size--;
            return nodeToRemove.data;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head == null;
        }
    }
}
