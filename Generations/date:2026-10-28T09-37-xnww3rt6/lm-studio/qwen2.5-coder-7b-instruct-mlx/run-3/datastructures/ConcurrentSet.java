```java
public class ConcurrentSet {
    private final Object[] elements;
    private int size;
    private static final Object lock = new Object();

    public ConcurrentSet(int capacity) {
        elements = new Object[capacity];
    }

    public boolean add(Object o) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(o)) {
                    return false;
                }
            }
            elements[size++] = o;
            lock.notifyAll();
            return true;
        }
    }

    public boolean remove(Object o) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(o)) {
                    System.arraycopy(elements, i + 1, elements, i, size - i - 1);
                    elements[--size] = null;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object o) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(o)) {
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
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
        }
    }
}
```
