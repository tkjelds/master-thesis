@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}

    private static final int DEFAULT_SIZE = 16;
    private Object[] elements;
    private volatile int size;

    public ConcurrentSet() {
        this.elements = new Object[DEFAULT_SIZE];
        this.size = 0;
    }

    public synchronized boolean add(Object element) {
        if (contains(element)) {
            return false;
        }
        ensureCapacity();
        elements[size] = element;
        size++;
        notifyAll();
        return true;
    }

    public synchronized boolean remove(Object element) {
        int index = -1;
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            return false;
        }
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
        }
        elements[--size] = null;
        notifyAll();
        return true;
    }

    public synchronized boolean contains(Object element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    private synchronized void ensureCapacity() {
        if (size == elements.length) {
            Object[] newElements = new Object[elements.length * 2];
            System.arraycopy(elements, 0, newElements, 0, size);
            elements = newElements;
        }
    }
}
