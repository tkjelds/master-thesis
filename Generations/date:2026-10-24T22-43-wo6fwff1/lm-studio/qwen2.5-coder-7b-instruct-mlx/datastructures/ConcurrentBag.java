```java
public class ConcurrentBag<T> {
    private T[] elements;
    private int size;
    private final Object lock = new Object();

    @SuppressWarnings("unchecked")
    public ConcurrentBag(int capacity) {
        this.elements = (T[]) new Object[capacity];
        this.size = 0;
    }

    public void add(T item) throws InterruptedException {
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[size++] = item;
            lock.notifyAll();
        }
    }

    public T remove() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T item = elements[--size];
            lock.notifyAll();
            return item;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(item)) {
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public void clear() {
        synchronized (lock) {
            while (size > 0) {
                elements[--size] = null;
            }
            lock.notifyAll();
        }
    }
}
```
