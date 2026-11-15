```java
public class LinkedQueue<T> {
    private static class Node<T> {
        T item;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private Node<T> head;
    private Node<T> tail;
    private int size;

    public LinkedQueue() {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        this.size = 0;
    }

    public synchronized void enqueue(T item) {
        Node<T> newNode = new Node<>(item);
        tail.next = newNode;
        tail = newNode;
        size++;
        notifyAll();
    }

    public synchronized T dequeue() throws InterruptedException {
        while (head.next == tail) {
            wait();
        }
        Node<T> dequeuedNode = head.next;
        head = head.next;
        if (head == tail) {
            tail = head;
        }
        size--;
        return dequeuedNode.item;
    }

    public synchronized int size() {
        return this.size;
    }
}
```
