@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}


    private class Node {
        private T data;
        private Node next;

        public Node(T data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node head;
    private int size;

    public ConcurrentLinkedList() {
        this.head = null;
        this.size = 0;
    }

    public void addFirst(T data) {
        if (data == null) throw new IllegalArgumentException("Data cannot be null");
        Node newNode = new Node(data);
        synchronized (this) {
            newNode.next = head;
            head = newNode;
            size++;
        }
    }

    public void addLast(T data) {
        if (data == null) throw new IllegalArgumentException("Data cannot be null");
        Node newNode = new Node(data);
        synchronized (this) {
            if (head == null) {
                head = newNode;
            } else {
                Node current = head;
                while (current.next != null) {
                    current = current.next;
                }
                current.next = newNode;
            }
            size++;
        }
    }

    public boolean remove(T data) {
        if (data == null) return false;
        synchronized (this) {
            if (head == null) {
                return false;
            }

            if (head.data.equals(data)) {
                head = head.next;
                size--;
                return true;
            }

            Node current = head;
            while (current.next != null) {
                if (current.next.data.equals(data)) {
                    current.next = current.next.next;
                    size--;
                    return true;
                }
                current = current.next;
            }
        }
        return false;
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

    public boolean contains(T data) {
        if (data == null) return false;
        synchronized (this) {
            Node current = head;
            while (current != null) {
                if (current.data.equals(data)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public T get(int index) {
        synchronized (this) {
            if (index < 0 || index >= size) {
                return null;
            }
            Node current = head;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current.data;
        }
    }

    public void clear() {
        synchronized (this) {
            head = null;
            size = 0;
        }
    }

    public boolean removeFirst() {
        synchronized (this) {
            if (head == null) {
                return false;
            }
            head = head.next;
            size--;
            return true;
        }
    }

    public boolean removeLast() {
        synchronized (this) {
            if (head == null) {
                return false;
            }
            if (head.next == null) {
                head = null;
                size--;
                return true;
            }

            Node current = head;
            while (current.next.next != null) {
                current = current.next;
            }
            current.next = null;
            size--;
            return true;
        }
    }
}
