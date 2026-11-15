```java
public class LinkedQueue<T> {
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

    public LinkedQueue() {
        this.head = new Node<>(null);
        this.tail = head;
    }

    public boolean enqueue(T item) {
        synchronized (head) {
            Node<T> newNode = new Node<>(item);
            tail.next = newNode;
            tail = newNode;
            head.notifyAll();
            return true;
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (head) {
            while (head == tail) {
                head.wait();
            }
            Node<T> node = head.next;
            T item = node.item;
            if (node == tail) {
                tail = head;
            }
            return item;
        }
    }

    public boolean isEmpty() {
        synchronized (head) {
            return head == tail;
        }
    }

    public int size() {
        synchronized (head) {
            int count = 0;
            Node<T> current = head.next;
            while (current != null) {
                count++;
                current = current.next;
            }
            return count;
        }
    }
}
```
