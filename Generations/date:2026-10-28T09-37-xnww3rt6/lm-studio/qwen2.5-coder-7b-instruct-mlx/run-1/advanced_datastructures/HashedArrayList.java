import java.util.Objects;

@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private T[] array;
    private Entry<T>[] entries;
    private int size;
    private int capacity;
    private int threshold;

    @SuppressWarnings("unchecked")
    public HashedArrayList() {
        capacity = DEFAULT_CAPACITY;
        threshold = (int) (capacity * LOAD_FACTOR);
        array = (T[]) new Object[capacity];
        entries = new Entry[capacity];
    }

    private static class Entry<T> {
        int index;
        Object hash;

        public Entry(int index, T item) {
            this.index = index;
            this.hash = Objects.hashCode(item);
        }
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        capacity *= 2;
        threshold = (int) (capacity * LOAD_FACTOR);
        T[] newArray = (T[]) new Object[capacity];
        Entry<T>[] newEntries = new Entry[capacity];

        for (int i = 0; i < size; ++i) {
            int newIndex = hashIndex(entries[i].hash);
            while (newEntries[newIndex] != null) {
                newIndex = (newIndex + 1) % capacity;
            }
            newEntries[newIndex] = entries[i];
            newArray[newIndex] = array[i];
        }

        array = newArray;
        entries = newEntries;
    }

    private int hashIndex(Object hash) {
        return Math.floorMod(Objects.hashCode(hash), capacity);
    }

    public synchronized boolean add(T item) {
        Objects.requireNonNull(item);
        return insert(size, item);
    }

    public synchronized boolean insert(int index, T item) {
        Objects.requireNonNull(item);

        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException();
        }

        if (size >= threshold) {
            resize();
        }

        int newIndex = size;
        int indexToRemove = hashIndex(item);
        while (entries[indexToRemove] != null) {
            if (Objects.equals(entries[indexToRemove].hash, item)) {
                return false;
            }
            indexToRemove = (indexToRemove + 1) % capacity;
        }

        entries[indexToRemove] = new Entry<>(newIndex, item);

        System.arraycopy(array, index, array, index + 1, size - index);
        array[index] = item;

        ++size;
        return true;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return array[index];
    }

    public synchronized T set(int index, T item) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
        Objects.requireNonNull(item);

        T oldItem = array[index];
        if (!Objects.equals(oldItem, item)) {
            int hashIndex = hashIndex(item);
            while (entries[hashIndex] != null) {
                if (Objects.equals(entries[hashIndex].hash, item)) {
                    throw new IllegalArgumentException();
                }
                hashIndex = (hashIndex + 1) % capacity;
            }

            entries[hashIndex] = new Entry<>(index, item);
        }
        array[index] = item;
        return oldItem;
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }

        T removedItem = array[index];

        int hashIndex = hashIndex(removedItem);
        while (entries[hashIndex] != null) {
            if (Objects.equals(entries[hashIndex].index, index)) {
                entries[hashIndex] = null;
                break;
            }
            hashIndex = (hashIndex + 1) % capacity;
        }

        System.arraycopy(array, index + 1, array, index, size - index - 1);
        array[--size] = null;

        return removedItem;
    }

    public synchronized boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        int hashIndex = hashIndex(item);
        while (entries[hashIndex] != null) {
            if (Objects.equals(entries[hashIndex].hash, item)) {
                entries[hashIndex] = null;
                int indexToRemove = hashIndex;

                hashIndex = (hashIndex + 1) % capacity;
                while (entries[hashIndex] != null) {
                    int newIndex = hashIndex(entries[hashIndex].hash);
                    while (newIndex == indexToRemove || entries[newIndex] != null) {
                        newIndex = (newIndex + 1) % capacity;
                    }
                    entries[newIndex] = entries[hashIndex];
                    array[newIndex] = array[hashIndex];

                    hashIndex = (hashIndex + 1) % capacity;
                }

                System.arraycopy(array, indexToRemove + 1, array, indexToRemove, size - indexToRemove);
                array[--size] = null;
                return true;
            }
            hashIndex = (hashIndex + 1) % capacity;
        }

        return false;
    }

    public synchronized boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        int hashIndex = hashIndex(item);
        while (entries[hashIndex] != null) {
            if (Objects.equals(entries[hashIndex].hash, item)) {
                return true;
            }
            hashIndex = (hashIndex + 1) % capacity;
        }

        return false;
    }

    public synchronized int indexOf(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        int hashIndex = hashIndex(item);
        while (entries[hashIndex] != null) {
            if (Objects.equals(entries[hashIndex].hash, item)) {
                return entries[hashIndex].index;
            }
            hashIndex = (hashIndex + 1) % capacity;
        }

        return -1;
    }

    public synchronized int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    public synchronized Object[] snapshot() {
        return array.clone();
    }
}
