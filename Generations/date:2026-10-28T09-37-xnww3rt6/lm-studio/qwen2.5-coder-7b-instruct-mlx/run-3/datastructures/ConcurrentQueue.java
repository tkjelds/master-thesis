```java
public class ConcurrentQueue<T> {
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ConcurrentQueue(int capacity) {
        elements = new Object[capacity];
        head = 0;
        tail = 0;
        count = 0;
    }

    public synchronized void enqueue(T item) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        elements[tail] = item;
        tail = (tail + 1) % elements.length;
        count++;
        notifyAll();
    }

    public synchronized T dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        T item = (T) elements[head];
        head = (head + 1) % elements.length;
        count--;
        notifyAll();
        return item;
    }

    public synchronized T peek() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        return (T) elements[head];
    }

    private synchronized boolean isFull() {
        return count == elements.length;
    }

    private synchronized boolean isEmpty() {
        return count == 0;
    }
}
```
