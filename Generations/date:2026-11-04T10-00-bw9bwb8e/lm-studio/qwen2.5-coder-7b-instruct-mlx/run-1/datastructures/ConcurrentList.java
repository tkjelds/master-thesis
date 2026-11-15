@ConcurrentList.ThreadSafe
public class ConcurrentList<T> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;
    private static final int DEFAULT_CAPACITY = 10;

    public ConcurrentList() {
        elements = new Object[DEFAULT_CAPACITY];
        size = 0;
    }

    public synchronized void add(T element) {
        if (size == elements.length) {
            grow();
        }
        elements[size++] = element;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return (T) elements[index];
    }

    public synchronized T remove(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        T element = (T) elements[index];
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
        }
        elements[--size] = null;
        return element;
    }

    public synchronized int size() {
        return size;
    }

    private synchronized void grow() {
        Object[] newElements = new Object[elements.length * 2];
        System.arraycopy(elements, 0, newElements, 0, size);
        elements = newElements;
    }
}
