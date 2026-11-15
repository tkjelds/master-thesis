```java
public class ConcurrentLinkedList<T> {
    private static class Node<T> {
        T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
            this.next = null;
        }
    }

    private Node<T> head;
    private Node<T> tail;

    public ConcurrentLinkedList() {
        this.head = new Node<>(null);
        this.tail = new Node<>(null);
        head.next = tail;
    }

    public void add(T value) {
        synchronized (this) {
            Node<T> newNode = new Node<>(value);
            Node<T> last = tail;
            while (last.next != tail) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            last.next = newNode;
            notifyAll();
        }
    }

    public T remove() {
        synchronized (this) {
            Node<T> first = head.next;
            while (first == tail) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            T value = first.value;
            head.next = first.next;
            notifyAll();
            return value;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            Node<T> first = head.next;
            return first == tail;
        }
    }

    public int size() {
        synchronized (this) {
            Node<T> current = head.next;
            int count = 0;
            while (current != tail) {
                count++;
                current = current.next;
            }
            return count;
        }
    }
}
```
