```java
public class ConcurrentCollection<T> {
    private final T[] items;
    private int size;

    @SuppressWarnings("unchecked")
    public ConcurrentCollection(int capacity) {
        items = (T[]) new Object[capacity];
        size = 0;
    }

    public synchronized boolean add(T item) {
        if (isFull()) {
            throw new IllegalStateException("Collection is full");
        }
        items[size++] = item;
        notifyAll();
        return true;
    }

    public synchronized T remove() {
        while (isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        T item = items[0];
        for (int i = 1; i < size; i++) {
            items[i - 1] = items[i];
        }
        items[--size] = null;
        return item;
    }

    public synchronized boolean contains(T item) {
        for (int i = 0; i < size; i++) {
            if (items[i].equals(item)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    private boolean isFull() {
        return size == items.length;
    }

    private boolean isEmpty() {
        return size == 0;
    }
}
```
