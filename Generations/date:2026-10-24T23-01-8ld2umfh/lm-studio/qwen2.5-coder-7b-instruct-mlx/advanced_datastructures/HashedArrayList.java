@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private T[] array;
    private Object[] keys;
    private int size;
    private static final float LOAD_FACTOR = 0.75f;

    @SuppressWarnings("unchecked")
    public HashedArrayList() {
        array = (T[]) new Object[16];
        keys = new Object[16];
    }

    private void ensureCapacity() {
        if (size >= array.length * LOAD_FACTOR) {
            grow();
        }
    }

    private void grow() {
        int newSize = array.length * 2;
        @SuppressWarnings("unchecked")
        T[] newArray = (T[]) new Object[newSize];
        Object[] newKeys = new Object[newSize];
        for (int i = 0; i < size; i++) {
            int hash = ((T) keys[i]).hashCode();
            int idx = hash % newSize;
            while (newArray[idx] != null) {
                idx = (idx + 1) % newSize;
            }
            newArray[idx] = array[i];
            newKeys[idx] = keys[i];
        }
        array = newArray;
        keys = newKeys;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        ensureCapacity();
        int hash = item.hashCode();
        int index = hash % array.length;
        while (array[index] != null && !array[index].equals(item)) {
            index = (index + 1) % array.length;
        }
        if (array[index] == null) {
            array[index] = item;
            keys[index] = item;
            size++;
            return true;
        }
        return false;
    }

    public synchronized boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        ensureCapacity();
        int hash = item.hashCode();
        int idx = hash % array.length;
        while (array[idx] != null && !array[idx].equals(item)) {
            idx = (idx + 1) % array.length;
        }
        if (array[idx] == null) {
            System.arraycopy(array, index, array, index + 1, size - index);
            System.arraycopy(keys, index, keys, index + 1, size - index);
            array[index] = item;
            keys[index] = item;
            size++;
            return true;
        }
        return false;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return array[index];
    }

    public synchronized T set(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        T oldValue = array[index];
        if (!oldValue.equals(item)) {
            throw new IllegalArgumentException("Cannot replace item at index with equal item from another index");
        }
        array[index] = item;
        keys[index] = item;
        return oldValue;
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        T oldValue = array[index];
        System.arraycopy(array, index + 1, array, index, size - index - 1);
        System.arraycopy(keys, index + 1, keys, index, size - index - 1);
        array[--size] = null;
        keys[size] = null;
        return oldValue;
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        for (int i = 0; i < size; i++) {
            if (array[i].equals(item)) {
                removeAt(i);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        for (int i = 0; i < size; i++) {
            if (array[i].equals(item)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int indexOf(T item) {
        if (item == null) throw new NullPointerException();
        for (int i = 0; i < size; i++) {
            if (array[i].equals(item)) {
                return i;
            }
        }
        return -1;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized Object[] snapshot() {
        @SuppressWarnings("unchecked")
        T[] snapshot = (T[]) new Object[size];
        System.arraycopy(array, 0, snapshot, 0, size);
        return snapshot;
    }
}
