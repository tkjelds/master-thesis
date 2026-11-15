@ConcurrentList.ThreadSafe
public class ConcurrentList {
    @interface ThreadSafe {}

    private static class Node {
        Object item;
        Node next;

        Node(Object item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node head;
    private Node tail;

    public ConcurrentList() {
        this.head = null;
        this.tail = null;
    }

    public void add(Object item) {
        Node newNode = new Node(item);
        synchronized (lock) {
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
        }
    }

    public Object remove() {
        synchronized (lock) {
            while (head == null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            Object item = head.item;
            if (head.next == null) {
                tail = null;
            }
            head = head.next;
            return item;
        }
    }

    public Object peek() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            return head.item;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }

    public int size() {
        synchronized (lock) {
            Node current = head;
            int count = 0;

            while (current != null) {
                count++;
                current = current.next;
            }

            return count;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            lock.notifyAll();
        }
    }

    public void addAll(ConcurrentList other) {
        synchronized (lock) {
            Node current = other.head;
            while (current != null) {
                add(current.item);
                current = current.next;
            }
        }
    }

    public boolean contains(Object item) {
        synchronized (lock) {
            Node current = head;
            while (current != null) {
                if (item.equals(current.item)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public void removeAll(ConcurrentList other) {
        synchronized (lock) {
            Node current = head;
            Node prev = null;

            while (current != null) {
                if (other.contains(current.item)) {
                    Node toRemove = current;
                    current = current.next;

                    if (prev == null) {
                        head = current;
                    } else {
                        prev.next = current;
                    }

                    if (toRemove == tail) {
                        tail = prev;
                    }
                } else {
                    prev = current;
                    current = current.next;
                }
            }

            if (prev == tail) {
                tail = prev;
            }
        }
    }
}
