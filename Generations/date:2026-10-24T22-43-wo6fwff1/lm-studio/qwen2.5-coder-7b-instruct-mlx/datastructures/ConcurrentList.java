```java
public class ConcurrentList<T> {
    private final Object[] elements;
    private int size;

    public ConcurrentList(int capacity) {
        this.elements = new Object[capacity];
        this.size = 0;
    }

    public synchronized boolean add(T element) {
        if (size == elements.length) {
            return false;
        }
        elements[size++] = element;
        notifyAll();
        return true;
    }

    public synchronized T remove() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        T element = (T) elements[--size];
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return size;
    }
}
```
