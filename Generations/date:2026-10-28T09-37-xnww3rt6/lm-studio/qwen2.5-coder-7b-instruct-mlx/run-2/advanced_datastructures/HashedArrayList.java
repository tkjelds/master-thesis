@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private T[] items;
    private Object[] hashes;
    private int size;

    @SuppressWarnings("unchecked")
    public HashedArrayList() {
        items = (T[]) new Object[INITIAL_CAPACITY];
        hashes = new Object[INITIAL_CAPACITY];
    }

    private void grow() {
        int newSize = items.length * 2;
        @SuppressWarnings("unchecked")
        T[] newItems = (T[]) new Object[newSize];
        Object[] newHashes = new Object[newSize];

        for (int i = 0; i < size; i++) {
            int newIndex = hashIndex(hashes[i]);
            while (newItems[newIndex] != null) {
                newIndex = (newIndex + 1) % newSize;
            }
            newItems[newIndex] = items[i];
            newHashes[newIndex] = hashes[i];
        }

        items = newItems;
        hashes = newHashes;
    }

    private int hashIndex(Object hash) {
        return Math.abs(hash.hashCode()) % items.length;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        int hashIndex = hashIndex(item.hashCode());
        while (hashes[hashIndex] != null) {
            if (items[hashIndex].equals(item)) return false;
            hashIndex = (hashIndex + 1) % items.length;
        }
        items[hashIndex] = item;
        hashes[hashIndex] = item.hashCode();
        if (size == items.length - 1) grow();
        size++;
        return true;
    }

    public synchronized boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();

        add(item);
        for (int i = size - 1; i > index; i--) {
            items[i] = items[i - 1];
            hashes[i] = hashes[i - 1];
        }
        items[index] = item;
        return true;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return items[index];
    }

    public synchronized T set(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();

        T oldValue = items[index];
        if (oldValue.equals(item)) return oldValue;

        int oldHashIndex = hashIndex(oldValue.hashCode());
        while (true) {
            if (hashes[oldHashIndex].equals(oldValue)) {
                hashes[oldHashIndex] = item.hashCode();
                break;
            }
            oldHashIndex = (oldHashIndex + 1) % items.length;
        }

        int newHashIndex = hashIndex(item.hashCode());
        while (hashes[newHashIndex] != null) {
            if (items[newHashIndex].equals(item)) return oldValue;
            newHashIndex = (newHashIndex + 1) % items.length;
        }

        items[index] = item;

        return oldValue;
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();

        T item = items[index];
        int hashIndex = hashIndex(item.hashCode());
        while (true) {
            if (hashes[hashIndex].equals(item)) {
                hashes[hashIndex] = null;
                break;
            }
            hashIndex = (hashIndex + 1) % items.length;
        }

        for (int i = index; i < size - 1; i++) {
            items[i] = items[i + 1];
            hashes[i] = hashes[i + 1];
        }
        items[size - 1] = null;
        hashes[size - 1] = null;
        size--;
        return item;
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();

        int hashIndex = hashIndex(item.hashCode());
        while (true) {
            if (hashes[hashIndex] == null) return false;
            if (items[hashIndex].equals(item)) {
                hashes[hashIndex] = null;
                break;
            }
            hashIndex = (hashIndex + 1) % items.length;
        }

        for (int i = hashIndex; true; ) {
            if (hashes[i] == null) break;
            i = (i + 1) % items.length;
        }

        for (int j = hashIndex; true; ) {
            if (hashes[j] == null) break;
            j = (j + 1) % items.length;
        }

        int newSize = items.length * 3 / 4;
        @SuppressWarnings("unchecked")
        T[] newItems = (T[]) new Object[newSize];
        Object[] newHashes = new Object[newSize];

        for (int i = 0; i < size - 1; i++) {
            int newIndex = hashIndex(items[i].hashCode());
            while (newItems[newIndex] != null) {
                newIndex = (newIndex + 1) % newSize;
            }
            newItems[newIndex] = items[i];
            newHashes[newIndex] = items[i].hashCode();
        }

        items = newItems;
        hashes = newHashes;
        size--;
        return true;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();

        int hashIndex = hashIndex(item.hashCode());
        while (true) {
            if (hashes[hashIndex] == null) return false;
            if (items[hashIndex].equals(item)) return true;
            hashIndex = (hashIndex + 1) % items.length;
        }
    }

    public synchronized int indexOf(T item) {
        if (item == null) throw new NullPointerException();

        int hashIndex = hashIndex(item.hashCode());
        while (true) {
            if (hashes[hashIndex] == null) return -1;
            if (items[hashIndex].equals(item)) return hashIndex;
            hashIndex = (hashIndex + 1) % items.length;
        }
    }

    public synchronized int size() {
        return size;
    }

    public synchronized Object[] snapshot() {
        @SuppressWarnings("unchecked")
        T[] snapshot = (T[]) new Object[size];
        System.arraycopy(items, 0, snapshot, 0, size);
        return snapshot;
    }
}
