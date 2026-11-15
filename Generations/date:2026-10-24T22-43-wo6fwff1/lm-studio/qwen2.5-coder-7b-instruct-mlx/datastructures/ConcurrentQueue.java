```java
public class ConcurrentQueue<T> {
    private final T[] elements;
    private int head, tail;

    @SuppressWarnings("unchecked")
    public ConcurrentQueue(int capacity) {
        elements = (T[]) new Object[capacity];
        head = tail = 0;
    }

    public synchronized void enqueue(T element) {
        while ((tail + 1) % elements.length == head) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        notifyAll();
    }

    public synchronized T dequeue() {
        while (head == tail) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        T element = elements[head];
        head = (head + 1) % elements.length;
        notifyAll();
        return element;
    }

    public synchronized boolean isEmpty() {
        return head == tail;
    }

    public synchronized int size() {
        return (tail - head + elements.length) % elements.length;
    }
}
```
