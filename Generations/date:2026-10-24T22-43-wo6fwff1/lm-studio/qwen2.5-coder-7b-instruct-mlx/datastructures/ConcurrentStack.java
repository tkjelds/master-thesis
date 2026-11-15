```java
public class ConcurrentStack<T> {
    private final Object[] elements;
    private volatile int size;

    public ConcurrentStack(int capacity) {
        elements = new Object[capacity];
        size = 0;
    }

    public synchronized void push(T element) {
        if (size == elements.length) {
            throw new IllegalStateException("Stack is full");
        }
        elements[size++] = element;
        notifyAll();
    }

    public synchronized T pop() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        T element = (T) elements[--size];
        elements[size] = null; // Help garbage collection
        return element;
    }

    public synchronized T peek() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        return (T) elements[size - 1];
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
```
