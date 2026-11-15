@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private Object[] array;
    private Object[] hashTable;
    private int size;

    public HashedArrayList() {
        this.array = new Object[DEFAULT_CAPACITY];
        this.hashTable = new Object[DEFAULT_CAPACITY];
        this.size = 0;
    }

    private void ensureCapacity() {
        if (size == array.length) {
            int newCapacity = array.length * 2;
            Object[] newArray = new Object[newCapacity];
            Object[] newHashTable = new Object[newCapacity];
            for (int i = 0; i < size; i++) {
                int indexInHashTable = ((T) array[i]).hashCode() % newCapacity;
                while (newHashTable[indexInHashTable] != null) {
                    indexInHashTable = (indexInHashTable + 1) % newCapacity;
                }
                newHashTable[indexInHashTable] = i;
            }
            array = newArray;
            hashTable = newHashTable;
        }
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        return insert(size, item);
    }

    public synchronized boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();

        ensureCapacity();
        
        int itemIndex = -1;
        for (int i = 0; i < hashTable.length; i++) {
            if (hashTable[i] != null && ((T) array[hashTable[i]]).equals(item)) {
                itemIndex = hashTable[i];
                break;
            }
        }

        if (itemIndex != -1) return false;

        for (int i = size; i > index; i--) {
            array[i] = array[i - 1];
        }

        array[index] = item;
        int indexInHashTable = item.hashCode() % hashTable.length;
        while (hashTable[indexInHashTable] != null) {
            indexInHashTable = (indexInHashTable + 1) % hashTable.length;
        }
        hashTable[indexInHashTable] = index;

        size++;
        return true;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return (T) array[index];
    }

    public synchronized T set(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();

        T oldValue = (T) array[index];
        for (int i = 0; i < hashTable.length; i++) {
            if (hashTable[i] != null && ((T) array[hashTable[i]]).equals(oldValue)) {
                int indexInHashTable = item.hashCode() % hashTable.length;
                while (hashTable[indexInHashTable] != null && hashTable[indexInHashTable] != index) {
                    indexInHashTable = (indexInHashTable + 1) % hashTable.length;
                }
                if (hashTable[indexInHashTable] == null) {
                    hashTable[indexInHashTable] = index;
                }
            }
        }

        array[index] = item;

        return oldValue;
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();

        T oldValue = (T) array[index];
        int indexInHashTable = oldValue.hashCode() % hashTable.length;
        while (hashTable[indexInHashTable] != null && hashTable[indexInHashTable] != index) {
            indexInHashTable = (indexInHashTable + 1) % hashTable.length;
        }
        if (hashTable[indexInHashTable] != null) {
            hashTable[indexInHashTable] = null;
        }

        for (int i = index; i < size - 1; i++) {
            array[i] = array[i + 1];
        }

        array[size - 1] = null;
        size--;

        return oldValue;
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        
        for (int i = 0; i < hashTable.length; i++) {
            if (hashTable[i] != null && ((T) array[hashTable[i]]).equals(item)) {
                int indexInHashTable = item.hashCode() % hashTable.length;
                while (hashTable[indexInHashTable] != null && hashTable[indexInHashTable] != i) {
                    indexInHashTable = (indexInHashTable + 1) % hashTable.length;
                }
                if (hashTable[indexInHashTable] != null) {
                    hashTable[indexInHashTable] = null;
                }

                for (int j = i; j < size - 1; j++) {
                    array[j] = array[j + 1];
                }

                array[size - 1] = null;
                size--;
                return true;
            }
        }

        return false;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        for (int i = 0; i < hashTable.length; i++) {
            if (hashTable[i] != null && ((T) array[hashTable[i]]).equals(item)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int indexOf(T item) {
        if (item == null) throw new NullPointerException();
        for (int i = 0; i < hashTable.length; i++) {
            if (hashTable[i] != null && ((T) array[hashTable[i]]).equals(item)) {
                return hashTable[i];
            }
        }
        return -1;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized Object[] snapshot() {
        Object[] result = new Object[size];
        System.arraycopy(array, 0, result, 0, size);
        return result;
    }
}
