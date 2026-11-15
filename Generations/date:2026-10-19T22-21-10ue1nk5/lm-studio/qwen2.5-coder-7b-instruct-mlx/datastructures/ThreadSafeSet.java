@ThreadSafeSet.ThreadSafe
public class ThreadSafeSet {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size;

    public ThreadSafeSet(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        elements = new Object[capacity];
    }

    public synchronized boolean add(Object element) {
        if (contains(element)) return false;

        for (int i = 0; i < size; i++) {
            if (elements[i] == null) {
                elements[i] = element;
                size++;
                return true;
            }
        }

        if (size == elements.length) resize();

        elements[size++] = element;
        return true;
    }

    public synchronized boolean remove(Object element) {
        if (!contains(element)) return false;

        for (int i = 0; i < size; i++) {
            if (elements[i] != null && elements[i].equals(element)) {
                while (i < size - 1) {
                    elements[i] = elements[i + 1];
                    i++;
                }
                elements[--size] = null;
                return true;
            }
        }

        return false; // should never reach here if contains(element) is true
    }

    public synchronized boolean contains(Object element) {
        for (int i = 0; i < size; i++) {
            if (elements[i] != null && elements[i].equals(element)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    private void resize() {
        Object[] newElements = new Object[elements.length * 2];
        System.arraycopy(elements, 0, newElements, 0, size);
        elements = newElements;
    }
}
