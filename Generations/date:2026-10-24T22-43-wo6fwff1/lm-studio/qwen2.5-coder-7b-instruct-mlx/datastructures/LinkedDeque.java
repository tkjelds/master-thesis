```java
public class LinkedDeque<T> {
    private static class Node<T> {
        T item;
        Node<T> next, prev;

        Node(T item) {
            this.item = item;
        }
    }

    private Node<T> head, tail;

    public synchronized void addFirst(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> newNode = new Node<>(item);
        if (head == null) {
            head = tail = newNode;
            return;
        }
        newNode.next = head;
        head.prev = newNode;
        head = newNode;
    }

    public synchronized void addLast(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> newNode = new Node<>(item);
        if (head == null) {
            head = tail = newNode;
            return;
        }
        newNode.prev = tail;
        tail.next = newNode;
        tail = newNode;
    }

    public synchronized T removeFirst() {
        if (head == null) throw new IllegalStateException("Deque is empty");
        T item = head.item;
        if (head == tail) {
            head = tail = null;
        } else {
            head = head.next;
            head.prev = null;
        }
        return item;
    }

    public synchronized T removeLast() {
        if (tail == null) throw new IllegalStateException("Deque is empty");
        T item = tail.item;
        if (head == tail) {
            head = tail = null;
        } else {
            tail = tail.prev;
            tail.next = null;
        }
        return item;
    }

    public synchronized T peekFirst() {
        return head == null ? null : head.item;
    }

    public synchronized T peekLast() {
        return tail == null ? null : tail.item;
    }

    public synchronized boolean isEmpty() {
        return head == null;
    }

    public synchronized int size() {
        int count = 0;
        for (Node<T> node = head; node != null; node = node.next) {
            count++;
        }
        return count;
    }

    private static void checkNotEmpty() throws IllegalStateException {
        if (head == null) throw new IllegalStateException("Deque is empty");
    }

    private static <T> T checkNotNull(T item) {
        if (item == null) throw new NullPointerException();
        return item;
    }
}
```
