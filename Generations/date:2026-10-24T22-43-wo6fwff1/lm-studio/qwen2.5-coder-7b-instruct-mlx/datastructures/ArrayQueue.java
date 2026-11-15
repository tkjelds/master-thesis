```java
public class ArrayQueue {
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ArrayQueue(int capacity) {
        elements = new Object[capacity];
        head = 0;
        tail = 0;
        count = 0;
    }

    public void enqueue(Object item) throws InterruptedException {
        synchronized (this) {
            while (count == elements.length) {
                wait();
            }
            elements[tail] = item;
            tail = (tail + 1) % elements.length;
            count++;
            notifyAll();
        }
    }

    public Object dequeue() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            Object item = elements[head];
            head = (head + 1) % elements.length;
            count--;
            notifyAll();
            return item;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return count == elements.length;
        }
    }
}
```
