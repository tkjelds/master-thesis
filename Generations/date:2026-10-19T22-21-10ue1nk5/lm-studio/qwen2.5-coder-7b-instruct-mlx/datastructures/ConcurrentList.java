@ConcurrentList.ThreadSafe
public class ConcurrentList {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size;

    public ConcurrentList(int capacity) {
        elements = new Object[capacity];
        size = 0;
    }

    public synchronized void add(Object element) {
        if (size == elements.length) {
            throw new IllegalStateException("List is full");
        }
        elements[size++] = element;
    }

    public synchronized Object get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Invalid index");
        }
        return elements[index];
    }

    public synchronized boolean remove(Object element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                System.arraycopy(elements, i + 1, elements, i, size - i - 1);
                elements[--size] = null;
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }
}
