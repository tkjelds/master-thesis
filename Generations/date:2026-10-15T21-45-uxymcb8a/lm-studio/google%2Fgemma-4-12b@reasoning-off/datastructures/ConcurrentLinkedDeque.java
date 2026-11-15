@ConcurrentLinkedDeque.ThreadSafe
public class ConcurrentLinkedDeque<T> {
    @interface ThreadSafe {}


    private class Node {
        T data;
        Node next;
        Node prev;

        Node(T data) {
            this.data = data;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public ConcurrentLinkedDeque() {
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    public void addFirst(T item) {
        if (item == null) throw new NullPointerException();
        Node newNode = new Node(item);
        synchronized (this) {
            if (head == null) {
                head = newNode;
                tail = newNode;
                newNode.prev = null;
                newNode.next = null;
            } else {
                newNode.next = head;
                head.prev = newNode;
                head = newNode;
                newNode.prev = null;
            }
            size++;
        }
    }

    public void addLast(T item) {
        if (item == null) throw new NullPointerException();
        Node newNode = new Node(item);
        synchronized (this) {
            if (tail == null) {
                head = newNode;
                tail = newNode;
                newNode.prev = null;
                newNode.next = null;
            } else {
                tail.next = newNode;
                newNode.prev = tail;
                tail = newNode;
                newNode.next = null;
            }
            size++;
        }
    }

    public T removeFirst() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                (this).wait();
            }
            Node first = head;
            head = head.next;
            if (head == null) {
                tail = null;
            } else {
                head.prev = null;
            }
            first.prev = null;
            first.next = null;
            size--;
            return first.data;
        }
    }

    public T removeLast() throws InterruptedException {
        synchronized (this) {
            while (tail == null) {
                (this).wait();
            }
            Node last = tail;
            tail = tail.prev;
            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }
            last.next = null;
            last.prev = null;
            size--;
            return last.data;
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

    public boolean isNotEmpty() {
        synchronized (this) {
            return head != null;
        }
    }

    public boolean removeFirst() throws InterruptedException {
        synchronized (this) {
            if (head == null) {
                return false;
            }
            Node first = head;
            head = head.next;
            if (head == null) {
                tail = null;
            } else {
                head.prev = null;
            }
            first.prev = null;
            first.next = null;
            size--;
            return true;
        }
    }

    public boolean removeLast() {
        synchronized (this) {
            if (tail == null) {
                return false;
            }
            Node last = tail;
            tail = tail.prev;
            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }
            last.next = null;
            last.prev = null;
            size--;
            return true;
        }
    }
}
