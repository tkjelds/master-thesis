```java
public class ConcurrentDeque<T> {
    private final Object[] elements;
    private int size;
    private int head;
    private int tail;

    public ConcurrentDeque(int capacity) {
        elements = new Object[capacity];
        size = 0;
        head = 0;
        tail = -1;
    }

    public synchronized boolean add(T element) {
        if (size == elements.length) {
            return false;
        }
        elements[tail = (tail + 1) % elements.length] = element;
        size++;
        notifyAll();
        return true;
    }

    public synchronized T poll() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        T element = (T) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        notifyAll();
        return element;
    }

    public synchronized T peek() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        T element = (T) elements[head];
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
```
