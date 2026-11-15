```java
public class BoundedConcurrentDeque<T> {
    private final T[] items;
    private int head;
    private int tail;
    private int size;

    @SuppressWarnings("unchecked")
    public BoundedConcurrentDeque(int capacity) {
        items = (T[]) new Object[capacity];
        head = 0;
        tail = 0;
        size = 0;
    }

    public synchronized void addFirst(T item) throws InterruptedException {
        while (size == items.length) {
            wait();
        }
        if (head == 0) {
            head = items.length - 1;
        } else {
            head--;
        }
        items[head] = item;
        size++;
        notifyAll();
    }

    public synchronized void addLast(T item) throws InterruptedException {
        while (size == items.length) {
            wait();
        }
        items[tail] = item;
        tail++;
        if (tail == items.length) {
            tail = 0;
        }
        size++;
        notifyAll();
    }

    public synchronized T removeFirst() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        T item = items[head];
        if (head == items.length - 1) {
            head = 0;
        } else {
            head++;
        }
        size--;
        notifyAll();
        return item;
    }

    public synchronized T removeLast() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        if (tail == 0) {
            tail = items.length - 1;
        } else {
            tail--;
        }
        T item = items[tail];
        size--;
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return size;
    }
}
```
