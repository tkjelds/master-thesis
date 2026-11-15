@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private T[] array;
    private Object[][] hashIndex;
    private int size;

    @SuppressWarnings("unchecked")
    public HashedArrayList() {
        array = (T[]) new Object[INITIAL_CAPACITY];
        hashIndex = new Object[INITIAL_CAPACITY][2];
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        int index = findIndex(item);
        if (index != -1) return false;
        addAt(size, item);
        return true;
    }

    public synchronized boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        int existingIndex = findIndex(item);
        if (existingIndex != -1 && existingIndex != index) throw new IllegalArgumentException();
        addAt(index, item);
        return true;
    }

    private synchronized void addAt(int index, T item) {
        ensureCapacity();
        shiftRight(index);
        array[index] = item;
        updateHashIndex(item, index);
        size++;
    }

    private void ensureCapacity() {
        if (size >= array.length * LOAD_FACTOR) {
            resize(array.length * 2);
        }
    }

    private void shiftRight(int index) {
        for (int i = size; i > index; i--) {
            array[i] = array[i - 1];
        }
    }

    private synchronized int findIndex(T item) {
        return Arrays.stream(hashIndex)
                .filter(pair -> pair != null && pair[0].equals(item))
                .mapToInt(pair -> (int) pair[1])
                .findFirst()
                .orElse(-1);
    }

    private int hash(Object obj) {
        return obj.hashCode() & (array.length - 1);
    }

    private synchronized void updateHashIndex(T item, int index) {
        int hashValue = hash(item);
        while (hashIndex[hashValue] != null && !hashIndex[hashValue][0].equals(item)) {
            if (hashIndex[hashValue][0].hashCode() == item.hashCode()) break;
            hashValue = (hashValue + 1) % array.length;
        }
        hashIndex[hashValue] = new Object[]{item, (long) index};
    }

    private synchronized void resize(int newCapacity) {
        @SuppressWarnings("unchecked")
        T[] newArray = (T[]) new Object[newCapacity];
        Object[][] newHashIndex = new Object[newCapacity][2];
        for (int i = 0; i < size; i++) {
            T item = array[i];
            int index = (int) hashIndex[hash(item)][1];
            newArray[index] = item;
            newHashIndex[index] = hashIndex[hash(item)];
        }
        array = newArray;
        hashIndex = newHashIndex;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return array[index];
    }

    public synchronized T set(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        T oldItem = array[index];
        if (oldItem.equals(item)) return item;
        removeAt(index);
        addAt(index, item);
        return oldItem;
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        T item = array[index];
        shiftLeft(index);
        updateHashIndex(item, -1);
        size--;
        return item;
    }

    private void shiftLeft(int index) {
        for (int i = index; i < size - 1; i++) {
            array[i] = array[i + 1];
        }
    }

    public synchronized boolean remove(T item) {
        int index = findIndex(item);
        if (index == -1) return false;
        removeAt(index);
        return true;
    }

    public synchronized boolean contains(T item) {
        return findIndex(item) != -1;
    }

    public synchronized int indexOf(T item) {
        return findIndex(item);
    }

    public synchronized int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    public synchronized Object[] snapshot() {
        return Arrays.copyOf(array, size);
    }
}
