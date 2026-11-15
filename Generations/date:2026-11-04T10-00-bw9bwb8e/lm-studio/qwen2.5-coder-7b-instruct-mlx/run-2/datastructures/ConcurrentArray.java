import java.util.Arrays;

@ConcurrentArray.ThreadSafe
public class ConcurrentArray {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;

    public ConcurrentArray(int capacity) {
        elements = new Object[capacity];
        size = 0;
    }

    public synchronized void add(Object element) {
        if (size == elements.length) {
            grow();
        }
        elements[size++] = element;
    }

    public synchronized Object get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return elements[index];
    }

    public synchronized void remove(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        System.arraycopy(elements, index + 1, elements, index, size - index - 1);
        elements[--size] = null;
    }

    public synchronized int size() {
        return size;
    }

    private synchronized void grow() {
        elements = Arrays.copyOf(elements, elements.length * 2);
    }

    public static void main(String[] args) {
        ConcurrentArray array = new ConcurrentArray(10);
        array.add("Hello");
        array.add("World");
        System.out.println(array.get(0)); // Output: Hello
        array.remove(1);
        System.out.println(array.size()); // Output: 1
    }
}
