```java
public class ThreadSafeList<T> {
    private Node<T> head;
    private int size;

    public ThreadSafeList() {
        this.head = new Node<>(null);
        this.size = 0;
    }

    public synchronized void add(T value) {
        Node<T> newNode = new Node<>(value);
        Node<T> current = head;
        while (current.next != null) {
            current = current.next;
        }
        current.next = newNode;
        size++;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        Node<T> current = head.next;
        for (int i = 0; i < index; i++) {
            current = current.next;
        }
        return current.value;
    }

    public synchronized int size() {
        return size;
    }

    private static class Node<T> {
        T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
            this.next = null;
        }
    }
}
```
