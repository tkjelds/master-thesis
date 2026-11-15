@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private Object[] elements;
    private volatile int size;

    public ConcurrentSet() {
        this.elements = new Object[DEFAULT_CAPACITY];
    }

    public synchronized boolean add(Object element) {
        if (contains(element)) {
            return false;
        }
        ensureCapacity();
        elements[size++] = element;
        notifyAll();
        return true;
    }

    public synchronized boolean remove(Object element) {
        int index = indexOf(element);
        if (index == -1) {
            return false;
        }
        System.arraycopy(elements, index + 1, elements, index, size - index - 1);
        elements[--size] = null;
        notifyAll();
        return true;
    }

    public synchronized boolean contains(Object element) {
        if (element == null) {
            for (int i = 0; i < size; i++) {
                if (elements[i] == null) {
                    return true;
                }
            }
        } else {
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized boolean isEmpty() {
        while (true) {
            int currentSize = size;
            if (currentSize == 0) {
                return true;
            }
        }
    }

    public synchronized int size() {
        while (true) {
            int currentSize = size;
            if (currentSize >= 0) {
                return currentSize;
            }
        }
    }

    private synchronized void ensureCapacity() {
        while (true) {
            int currentSize = size, capacity = elements.length;
            if (currentSize < capacity) {
                return;
            }
            Object[] newElements = new Object[capacity * 2];
            System.arraycopy(elements, 0, newElements, 0, capacity);
            elements = newElements;
        }
    }

    private synchronized int indexOf(Object element) {
        if (element == null) {
            for (int i = 0; i < size; i++) {
                if (elements[i] == null) {
                    return i;
                }
            }
        } else {
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
                    return i;
                }
            }
        }
        return -1;
    }

    public synchronized void clear() {
        while (true) {
            int currentSize = size, capacity = elements.length;
            for (int i = 0; i < currentSize; i++) {
                elements[i] = null;
            }
            size = 0;
            notifyAll();
            return;
        }
    }
}
