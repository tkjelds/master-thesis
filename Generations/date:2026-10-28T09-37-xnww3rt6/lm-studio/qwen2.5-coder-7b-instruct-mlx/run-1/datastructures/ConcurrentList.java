@ConcurrentList.ThreadSafe
public class ConcurrentList {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;

    public ConcurrentList() {
        this.elements = new Object[10];
        this.size = 0;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized void add(Object element) {
        if (size >= elements.length) {
            grow();
        }
        elements[size++] = element;
        notifyAll();
    }

    public synchronized Object get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return elements[index];
    }

    public synchronized Object remove(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        Object oldValue = elements[index];
        System.arraycopy(elements, index + 1, elements, index, size - index - 1);
        elements[--size] = null;
        notifyAll();
        return oldValue;
    }

    private synchronized void grow() {
        int newCapacity = elements.length * 2;
        Object[] newElements = new Object[newCapacity];
        System.arraycopy(elements, 0, newElements, 0, size);
        elements = newElements;
    }
}
