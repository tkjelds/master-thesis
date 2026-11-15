@LinkedDeque.ThreadSafe
public class LinkedDeque {
    @interface ThreadSafe {}

    private static class Node {
        Object item;
        Node prev;
        Node next;

        Node(Object item, Node prev, Node next) {
            this.item = item;
            this.prev = prev;
            this.next = next;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public LinkedDeque() {
        this.head = new Node(null, null, null);
        this.tail = new Node(null, head, null);
        head.next = tail;
    }

    public void addFirst(Object item) {
        Node newNode = new Node(item, head.prev, head);
        synchronized (this) {
            head.prev.next = newNode;
            head.prev = newNode;
            size++;
        }
    }

    public void addLast(Object item) {
        Node newNode = new Node(item, tail.prev, tail);
        synchronized (this) {
            tail.prev.next = newNode;
            tail.prev = newNode;
            size++;
        }
    }

    public Object removeFirst() {
        synchronized (this) {
            while (head.next == tail && head.item != null) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (head.next == tail) {
                return null;
            }

            Node firstNode = head.next;
            Object item = firstNode.item;

            synchronized (firstNode) {
                head.next = firstNode.next;
                firstNode.next.prev = head;
            }

            size--;
            return item;
        }
    }

    public Object removeLast() {
        synchronized (this) {
            while (tail.prev == head && tail.item != null) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (tail.prev == head) {
                return null;
            }

            Node lastNode = tail.prev;
            Object item = lastNode.item;

            synchronized (lastNode) {
                tail.prev = lastNode.prev;
                lastNode.prev.next = tail;
            }

            size--;
            return item;
        }
    }

    public Object getFirst() {
        synchronized (this) {
            if (head.next == tail) {
                return null;
            }
            return head.next.item;
        }
    }

    public Object getLast() {
        synchronized (this) {
            if (tail.prev == head) {
                return null;
            }
            return tail.prev.item;
        }
    }

    public boolean contains(Object item) {
        synchronized (this) {
            Node current = head.next;
            while (current != tail) {
                if (item.equals(current.item)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head.next == tail;
        }
    }

    public void clear() {
        synchronized (this) {
            Node current = head.next;
            while (current != tail) {
                Node nextNode = current.next;
                synchronized (current) {
                    current.prev = null;
                    current.next = null;
                }
                current = nextNode;
            }
            head.next = tail;
            tail.prev = head;
            size = ++size - size; // Reset size
        }
    }

    public void notifyAdd() {
        synchronized (this) {
            this.notifyAll();
        }
    }

    public void notifyRemove() {
        synchronized (this) {
            this.notifyAll();
        }
    }

    public void waitForAdd() throws InterruptedException {
        synchronized (this) {
            while (isEmpty()) {
                this.wait();
            }
        }
    }

    public void waitForRemove() throws InterruptedException {
        synchronized (this) {
            while (!isEmpty()) {
                this.wait();
            }
        }
    }
}
