@ConcurrentList.ThreadSafe
public class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final Object[] EMPTY = new Object[0];
    private volatile Object[] elements;
    private int size;

    public ConcurrentList() {
        this.elements = EMPTY;
        this.size = 0;
    }

    public synchronized void add(E element) {
        Object[] newElements = new Object[size + 1];
        System.arraycopy(elements, 0, newElements, 0, size);
        newElements[size] = element;
        elements = newElements;
        size++;
    }

    public synchronized E get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return (E) elements[index];
    }

    public synchronized int size() {
        return size;
    }
}
