```java
public class ConcurrentLinkedList<T> {
    private static class Node<T> {
        T item;
        Node<T> next;

        Node(T item) {
            this.item = item;
            this.next = null;
        }
    }

    private Node<T> head;
    private Node<T> tail;

    public ConcurrentLinkedList() {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        tail.next = null;
    }

    public void add(T item) {
        synchronized (head) {
            Node<T> newNode = new Node<>(item);
            while (tail == head.next) {
                try {
                    head.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Node<T> last = tail;
            while (last.next != null) {
                last = last.next;
            }
            last.next = newNode;
            notifyAll();
        }
    }

    public T remove() {
        synchronized (tail) {
            while (head == tail.next) {
                try {
                    tail.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Node<T> first = head.next;
            T item = first.item;
            while (first != tail) {
                Node<T> next = first.next;
                if (next == null) {
                    break;
                }
                first.item = null;
                first.next = null;
                first = next;
            }
            head.next = tail;
            notifyAll();
            return item;
        }
    }

    public boolean isEmpty() {
        synchronized (head) {
            return head == tail.next;
        }
    }

    public int size() {
        synchronized (head) {
            int count = 0;
            Node<T> current = head.next;
            while (current != tail) {
                count++;
                current = current.next;
            }
            return count;
        }
    }

    public T peek() {
        synchronized (head) {
            if (isEmpty()) {
                return null;
            }
            Node<T> first = head.next;
            return first.item;
        }
    }
}
```
