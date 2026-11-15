@ConcurrentCollection.ThreadSafe
public class ConcurrentCollection {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;
    private final int capacity;

    public ConcurrentCollection(int capacity) {
        this.capacity = capacity;
        elements = new Object[capacity];
        size = 0;
    }

    public synchronized boolean add(Object element) {
        if (size == capacity) {
            return false;
        }
        elements[size++] = element;
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
        size--;
        notifyAll();
        return true;
    }

    public synchronized Object get(int index) throws IndexOutOfBoundsException {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return elements[index];
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized void clear() {
        elements = new Object[capacity];
        size = 0;
        notifyAll();
    }
}
