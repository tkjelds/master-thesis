```java
public class Bag<T> {
    private T[] elements;
    private int size;
    private final Object lock = new Object();

    @SuppressWarnings("unchecked")
    public Bag(int capacity) {
        elements = (T[]) new Object[capacity];
        size = 0;
    }

    public void add(T element) throws InterruptedException {
        synchronized (lock) {
            while (isFull()) {
                lock.wait();
            }
            elements[size++] = element;
            lock.notifyAll();
        }
    }

    public T remove() throws InterruptedException {
        T element;
        synchronized (lock) {
            while (isEmpty()) {
                lock.wait();
            }
            element = elements[--size];
            lock.notifyAll();
        }
        return element;
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    private boolean isFull() {
        return size == elements.length;
    }

    private boolean isEmpty() {
        return size == 0;
    }
}
```
