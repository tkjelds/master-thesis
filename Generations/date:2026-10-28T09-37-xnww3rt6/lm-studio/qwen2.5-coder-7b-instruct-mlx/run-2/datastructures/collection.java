@ThreadSafeCollection.ThreadSafe
class ThreadSafeCollection<T> {
    @interface ThreadSafe {}

    private T[] elements;
    private int size;

    @SuppressWarnings("unchecked")
    public ThreadSafeCollection(int capacity) {
        elements = (T[]) new Object[capacity];
        size = 0;
    }

    public synchronized void add(T element) {
        if (size == elements.length) {
            resize();
        }
        elements[size++] = element;
    }

    public synchronized T remove(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        T removedElement = elements[index];
        System.arraycopy(elements, index + 1, elements, index, size - index - 1);
        elements[--size] = null;
        return removedElement;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return elements[index];
    }

    public synchronized int getSize() {
        return size;
    }

    private void resize() {
        T[] newElements = (T[]) new Object[elements.length * 2];
        System.arraycopy(elements, 0, newElements, 0, elements.length);
        elements = newElements;
    }
}
