@ConcurrentList.ThreadSafe
public class ConcurrentList {
    @interface ThreadSafe {}

    private int[] array;
    private int size;
    private final Object mutex = new Object();

    public ConcurrentList(int initialCapacity) {
        array = new int[initialCapacity];
        size = 0;
    }

    public void add(int value) {
        synchronized (mutex) {
            if (size == array.length) {
                int[] newArray = new int[array.length * 2];
                System.arraycopy(array, 0, newArray, 0, array.length);
                array = newArray;
            }
            array[size++] = value;
        }
    }

    public void remove(int index) {
        synchronized (mutex) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            for (int i = index; i < size - 1; i++) {
                array[i] = array[i + 1];
            }
            size--;
        }
    }

    public int get(int index) {
        synchronized (mutex) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return array[index];
        }
    }

    public int size() {
        synchronized (mutex) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (mutex) {
            size = 0;
        }
    }
}
