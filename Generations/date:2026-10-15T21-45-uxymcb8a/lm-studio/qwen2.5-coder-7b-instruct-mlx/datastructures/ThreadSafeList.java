@ThreadSafeList.ThreadSafe
public class ThreadSafeList<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private volatile int size;

    public ThreadSafeList(int capacity) {
        if (capacity < 1)
            throw new IllegalArgumentException("Capacity must be at least 1");
        elements = new Object[capacity];
    }

    public synchronized void add(E element) {
        if (size == elements.length)
            throw new IllegalStateException("List is full");
        elements[size++] = element;
    }

    public synchronized E get(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index);
        return (E) elements[index];
    }

    public synchronized E remove(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index);
        E oldValue = (E) elements[index];
        System.arraycopy(elements, index + 1, elements, index, size - index - 1);
        elements[--size] = null;
        return oldValue;
    }

    public synchronized int size() {
        return size;
    }
}
