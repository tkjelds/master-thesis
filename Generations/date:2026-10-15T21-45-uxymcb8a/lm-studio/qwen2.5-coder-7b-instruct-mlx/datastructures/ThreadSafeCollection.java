@ThreadSafeCollection.ThreadSafe
public class ThreadSafeCollection<E> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;

    public ThreadSafeCollection(int initialCapacity) {
        elements = new Object[initialCapacity];
    }

    public synchronized void add(E element) {
        if (size == elements.length) {
            resize();
        }
        elements[size++] = element;
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

    private void resize() {
        Object[] newElements = new Object[elements.length * 2];
        System.arraycopy(elements, 0, newElements, 0, elements.length);
        elements = newElements;
    }
}
