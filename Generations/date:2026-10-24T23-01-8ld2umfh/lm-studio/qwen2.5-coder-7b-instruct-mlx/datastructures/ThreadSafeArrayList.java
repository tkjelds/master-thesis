@ThreadSafeArrayList.ThreadSafe
public class ThreadSafeArrayList<T> {
    @interface ThreadSafe {}

    private T[] array;
    private int size;
    private static final int DEFAULT_CAPACITY = 10;

    @SuppressWarnings("unchecked")
    public ThreadSafeArrayList() {
        array = (T[]) new Object[DEFAULT_CAPACITY];
        size = 0;
    }

    public synchronized void add(T item) {
        if (size == array.length) {
            resize();
        }
        array[size++] = item;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return array[index];
    }

    public synchronized T remove(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        T removedItem = array[index];
        for (int i = index; i < size - 1; i++) {
            array[i] = array[i + 1];
        }
        size--;
        return removedItem;
    }

    public synchronized int size() {
        return size;
    }

    private void resize() {
        @SuppressWarnings("unchecked")
        T[] newArray = (T[]) new Object[array.length * 2];
        System.arraycopy(array, 0, newArray, 0, size);
        array = newArray;
    }
}
