```java
public class ConcurrentSet {
    private static final int DEFAULT_CAPACITY = 16;
    private Object[] elements;
    private volatile int size;

    public ConcurrentSet() {
        this.elements = new Object[DEFAULT_CAPACITY];
        this.size = 0;
    }

    public synchronized boolean add(Object element) {
        if (contains(element)) {
            return false;
        }
        ensureCapacity();
        elements[size++] = element;
        return true;
    }

    public synchronized boolean remove(Object element) {
        int index = indexOf(element);
        if (index == -1) {
            return false;
        }
        removeAt(index);
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

    private int indexOf(Object element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                return i;
            }
        }
        return -1;
    }

    private void removeAt(int index) {
        System.arraycopy(elements, index + 1, elements, index, size - index - 1);
        elements[--size] = null;
    }

    private void ensureCapacity() {
        if (size >= elements.length) {
            Object[] newElements = new Object[elements.length * 2];
            System.arraycopy(elements, 0, newElements, 0, elements.length);
            elements = newElements;
        }
    }

    @Override
    public synchronized boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConcurrentSet)) return false;

        ConcurrentSet that = (ConcurrentSet) o;

        if (size != that.size) return false;
        for (int i = 0; i < size; i++) {
            if (!elements[i].equals(that.elements[i])) return false;
        }
        return true;
    }

    @Override
    public synchronized int hashCode() {
        int result = 17;
        for (int i = 0; i < size; i++) {
            result = 31 * result + elements[i].hashCode();
        }
        return result;
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < size; i++) {
            sb.append(elements[i]);
            if (i != size - 1) sb.append(", ");
        }
        sb.append(']');
        return sb.toString();
    }
}
```
