```java
public class ConcurrentStack<T> {
    private Object[] elements;
    private int size = 0;
    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    public ConcurrentStack() {
        elements = new Object[DEFAULT_INITIAL_CAPACITY];
    }

    private int calculateNewCapacity(int oldCapacity) {
        return Math.max(oldCapacity + (oldCapacity >> 1), 1);
    }

    public synchronized void push(T item) {
        ensureCapacity(size + 1);
        elements[size++] = item;
        notifyAll();
    }

    public synchronized T pop() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        return doPop();
    }

    private T doPop() {
        @SuppressWarnings("unchecked")
        T item = (T) elements[--size];
        elements[size] = null; // Help GC
        return item;
    }

    public synchronized T peek() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        return doPeek();
    }

    private T doPeek() {
        @SuppressWarnings("unchecked")
        T item = (T) elements[size - 1];
        return item;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    private void ensureCapacity(int minCapacity) {
        int oldCapacity = elements.length;
        if (minCapacity > oldCapacity) {
            grow(minCapacity);
        }
    }

    private void grow(int minCapacity) {
        int oldCapacity = elements.length;
        int newCapacity = calculateNewCapacity(oldCapacity);
        Object[] newElements = new Object[newCapacity];
        System.arraycopy(elements, 0, newElements, 0, size);
        elements = newElements;
    }
}
```
