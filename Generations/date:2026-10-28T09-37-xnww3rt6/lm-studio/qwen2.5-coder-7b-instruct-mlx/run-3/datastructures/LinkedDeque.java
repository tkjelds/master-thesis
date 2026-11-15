```java
public class LinkedDeque<T> {
    private static class Node<T> {
        T item;
        Node<T> prev, next;

        Node(T item) { this.item = item; }
    }

    private final Node<T> head = new Node<>(null);
    private final Node<T> tail = new Node<>(null);
    private int size = 0;

    public void addFirst(T item) {
        synchronized (head) {
            Node<T> newNode = new Node<>(item);
            newNode.next = head;
            newNode.prev = null;
            if (head.next != null) {
                head.next.prev = newNode;
            } else {
                tail.prev = newNode;
            }
            head.next = newNode;
            size++;
        }
    }

    public void addLast(T item) {
        synchronized (tail) {
            Node<T> newNode = new Node<>(item);
            newNode.prev = tail;
            newNode.next = null;
            if (tail.prev != null) {
                tail.prev.next = newNode;
            } else {
                head.next = newNode;
            }
            tailprev = newNode;
            size++;
        }
    }

    public T removeFirst() {
        synchronized (head) {
            if (head.next == null) throw new NoSuchElementException();
            Node<T> first = head.next;
            T item = first.item;
            Node<T> next = first.next;
            head.next = next;
            if (next != null) {
                next.prev = head;
            } else {
                tail.prev = head;
            }
            first.next = null;
            first.item = null;
            size--;
            return item;
        }
    }

    public T removeLast() {
        synchronized (tail) {
            if (tail.prev == null) throw new NoSuchElementException();
            Node<T> last = tail.prev;
            T item = last.item;
            Node<T> prev = last.prev;
            tail.prev = prev;
            if (prev != null) {
                prev.next = tail;
            } else {
                head.next = tail;
            }
            last.prev = null;
            last.item = null;
            size--;
            return item;
        }
    }

    public T getFirst() {
        synchronized (head) {
            if (head.next == null) throw new NoSuchElementException();
            return head.next.item;
        }
    }

    public T getLast() {
        synchronized (tail) {
            if (tail.prev == null) throw new NoSuchElementException();
            return tail.prev.item;
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
}
```
