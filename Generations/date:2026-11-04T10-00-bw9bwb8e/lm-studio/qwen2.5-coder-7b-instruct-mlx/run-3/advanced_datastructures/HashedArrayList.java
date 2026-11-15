@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final float LOAD_FACTOR = 0.75f;
    private static final int INITIAL_CAPACITY = 16;

    private T[] array;
    private Object[] hashIndex;
    private int size;
    private int capacity;

    @SuppressWarnings("unchecked")
    public HashedArrayList() {
        array = (T[]) new Object[INITIAL_CAPACITY];
        hashIndex = new Object[INITIAL_CAPACITY];
        capacity = INITIAL_CAPACITY;
    }

    private void ensureCapacity() {
        if (size >= capacity) {
            resize(capacity * 2);
        }
    }

    private void resize(int newCapacity) {
        @SuppressWarnings("unchecked")
        T[] newArray = (T[]) new Object[newCapacity];
        Object[] newHashIndex = new Object[newCapacity];

        System.arraycopy(array, 0, newArray, 0, size);
        System.arraycopy(hashIndex, 0, newHashIndex, 0, capacity);

        array = newArray;
        hashIndex = newHashIndex;
        capacity = newCapacity;
    }

    private int hashCode(Object o) {
        return System.identityHashCode(o);
    }

    private void rehash() {
        int newCapacity = capacity * 2;
        resize(newCapacity);
    }

    public synchronized boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        int hash = hashCode(item);
        int index = hash % capacity;
        while (hashIndex[index] != null) {
            if (item.equals(array[hashIndex[index]])) {
                return false;
            }
            index = (index + 1) % capacity;
        }

        hashIndex[index] = size;
        array[size++] = item;

        if ((float) size / capacity > LOAD_FACTOR) {
            rehash();
        }

        return true;
    }

    public synchronized boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }

        ensureCapacity();
        int hash = hashCode(item);

        for (int i = 0; i < capacity; i++) {
            int newIndex = (hash + i) % capacity;
            if (hashIndex[newIndex] == null) {
                hashIndex[newIndex] = size;
                array[size++] = item;
                return true;
            } else if (item.equals(array[hashIndex[newIndex]])) {
                return false;
            }
        }

        rehash();
        return insert(index, item);
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return array[index];
    }

    public synchronized T set(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        if (array[index].equals(item)) {
            return item;
        }

        int oldIndex = hashIndex[index];
        array[oldIndex] = item;

        for (int i = 0; i < capacity; i++) {
            if (hashIndex[i] == oldIndex) {
                hashIndex[i] = index;
                break;
            }
        }

        array[oldIndex] = null;

        for (int i = 0; i < capacity; i++) {
            if (hashIndex[i] == index) {
                hashIndex[i] = oldIndex;
                break;
            }
        }

        return array[index];
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }

        int oldItemIndex = hashIndex[index];
        T removedItem = array[oldItemIndex];

        for (int i = 0; i < capacity; i++) {
            if (hashIndex[i] == oldItemIndex) {
                hashIndex[i] = null;
                break;
            }
        }

        array[oldItemIndex] = null;

        for (int i = index; i < size - 1; i++) {
            array[i] = array[i + 1];
        }

        array[--size] = null;

        return removedItem;
    }

    public synchronized boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        for (int i = 0; i < capacity; i++) {
            if (hashIndex[i] != null && array[hashIndex[i]].equals(item)) {
                int oldItemIndex = hashIndex[i];
                for (int j = i; j < capacity; j++) {
                    if (hashIndex[j] == oldItemIndex) {
                        hashIndex[j] = null;
                        break;
                    }
                }

                array[oldItemIndex] = null;

                for (int j = 0; j < size - 1; j++) {
                    array[j] = array[j + 1];
                }

                array[--size] = null;

                return true;
            }
        }

        return false;
    }

    public synchronized boolean contains(T item) {
        for (int i = 0; i < capacity; i++) {
            if (hashIndex[i] != null && array[hashIndex[i]].equals(item)) {
                return true;
            }
        }

        return false;
    }

    public synchronized int indexOf(T item) {
        for (int i = 0; i < capacity; i++) {
            if (hashIndex[i] != null && array[hashIndex[i]].equals(item)) {
                return hashIndex[i];
            }
        }

        return -1;
    }

    public synchronized int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    public synchronized Object[] snapshot() {
        return (Object[]) array.clone();
    }
}
