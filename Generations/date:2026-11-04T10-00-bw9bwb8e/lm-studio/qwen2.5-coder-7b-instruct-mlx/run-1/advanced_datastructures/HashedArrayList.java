@HashedArrayList.ThreadSafe
class HashedArrayList<T> {
    @interface ThreadSafe {}

    private T[] array;
    private int size;
    private Object[] hashIndex;

    @SuppressWarnings("unchecked")
    public HashedArrayList() {
        array = (T[]) new Object[16];
        hashIndex = new Object[array.length];
    }

    private synchronized int findIndex(Object item) {
        int hash = item.hashCode();
        for (int i = 0; i < hashIndex.length; i++) {
            if (hashIndex[i] == item) {
                return i;
            }
        }
        return -1;
    }

    private synchronized void rehash() {
        int newSize = array.length * 2;
        T[] newArray = (T[]) new Object[newSize];
        Object[] newHashIndex = new Object[newSize];

        for (int i = 0; i < array.length; i++) {
            if (array[i] != null) {
                int hash = array[i].hashCode();
                while (newHashIndex[hash % newSize] != null) {
                    hash++;
                }
                newArray[hash % newSize] = array[i];
                newHashIndex[hash % newSize] = i;
            }
        }

        array = newArray;
        hashIndex = newHashIndex;
    }

    public synchronized boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        int hash = item.hashCode();
        while (hashIndex[hash % array.length] != null) {
            hash++;
        }

        if (findIndex(item) >= 0) {
            return false;
        }

        array[hash % array.length] = item;
        hashIndex[hash % array.length] = size++;
        return true;
    }

    public synchronized boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException();
        }

        if (findIndex(item) >= 0) {
            return false;
        }

        resize();
        for (int i = size; i > index; i--) {
            array[i] = array[i - 1];
        }
        array[index] = item;
        hashIndex[item.hashCode()] = index;
        size++;
        return true;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return array[index];
    }

    public synchronized T set(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }

        if (!array[index].equals(item)) {
            T oldItem = array[index];
            int oldIndex = findIndex(oldItem);
            hashIndex[item.hashCode()] = index;
            array[index] = item;
            hashIndex[oldItem.hashCode()] = null;
            return oldItem;
        }

        return array[index];
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }

        T item = array[index];
        hashIndex[item.hashCode()] = null;
        for (int i = index; i < size - 1; i++) {
            array[i] = array[i + 1];
        }
        array[--size] = null;
        return item;
    }

    public synchronized boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        int index = findIndex(item);
        if (index < 0) {
            return false;
        }

        removeAt(index);
        return true;
    }

    public synchronized boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        return findIndex(item) >= 0;
    }

    public synchronized int indexOf(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        return findIndex(item);
    }

    public synchronized int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    private synchronized void resize() {
        if (array.length == size) {
            rehash();
        }
    }

    public synchronized Object[] snapshot() {
        T[] copy = (T[]) new Object[size];
        System.arraycopy(array, 0, copy, 0, size);
        return copy;
    }
}
