@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}

    private final static Object[] EMPTY_ARRAY = {};
    private volatile Object[] elements;
    private int size;

    public ConcurrentSet() {
        elements = EMPTY_ARRAY;
    }

    public boolean add(Object o) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(o)) {
                    return false;
                }
            }
            Object[] newArray = new Object[size + 1];
            System.arraycopy(elements, 0, newArray, 0, size);
            newArray[size] = o;
            elements = newArray;
            size++;
            return true;
        }
    }

    public boolean remove(Object o) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(o)) {
                    Object[] newArray = new Object[size - 1];
                    System.arraycopy(elements, 0, newArray, 0, i);
                    System.arraycopy(elements, i + 1, newArray, i, size - i - 1);
                    elements = newArray;
                    size--;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object o) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(o)) {
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }
}
