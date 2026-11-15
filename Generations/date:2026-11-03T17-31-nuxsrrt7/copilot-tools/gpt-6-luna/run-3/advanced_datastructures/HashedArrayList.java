@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_ARRAY_CAPACITY = 8;
    private static final int INITIAL_TABLE_CAPACITY = 16;

    private final Object lock = new Object();
    private Object[] elements = new Object[INITIAL_ARRAY_CAPACITY];
    private Entry<T>[] positions = newEntryArray(INITIAL_ARRAY_CAPACITY);
    private Entry<T>[] buckets = newEntryArray(INITIAL_TABLE_CAPACITY);
    private int size;

    private static final class Entry<E> {
        E item;
        final int hash;
        int index;
        Entry<E> next;

        Entry(E item, int hash, int index) {
            this.item = item;
            this.hash = hash;
            this.index = index;
        }
    }

    private static <E> Entry<E>[] newEntryArray(int length) {
        return (Entry<E>[]) new Entry<?>[length];
    }

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private static void checkInsertIndex(int index, int size) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private void ensureArrayCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        if (elements.length >= (1 << 30)) {
            throw new OutOfMemoryError("Required array size too large");
        }

        int capacity = elements.length << 1;
        if (capacity < required) {
            capacity = required;
        }

        Object[] newElements = new Object[capacity];
        Entry<T>[] newPositions = newEntryArray(capacity);
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[i];
            newPositions[i] = positions[i];
        }
        elements = newElements;
        positions = newPositions;
    }

    private void ensureHashCapacity(int required) {
        if (required <= buckets.length - buckets.length / 4) {
            return;
        }

        if (buckets.length >= (1 << 30)) {
            throw new OutOfMemoryError("Required hash table size too large");
        }

        int capacity = buckets.length << 1;
        Entry<T>[] newBuckets = newEntryArray(capacity);
        Entry<T>[] newPositions = newEntryArray(positions.length);

        for (int i = 0; i < size; i++) {
            Entry<T> oldEntry = positions[i];
            Entry<T> newEntry = new Entry<>(oldEntry.item, oldEntry.hash, i);
            int bucket = newEntry.hash & (capacity - 1);
            newEntry.next = newBuckets[bucket];
            newBuckets[bucket] = newEntry;
            newPositions[i] = newEntry;
        }

        buckets = newBuckets;
        positions = newPositions;
    }

    private Entry<T> findEntry(T item, int hash) {
        int bucket = hash & (buckets.length - 1);
        for (Entry<T> entry = buckets[bucket]; entry != null; entry = entry.next) {
            if (entry.hash == hash && item.equals(entry.item)) {
                return entry;
            }
        }
        return null;
    }

    private void linkEntry(Entry<T> entry) {
        int bucket = entry.hash & (buckets.length - 1);
        entry.next = buckets[bucket];
        buckets[bucket] = entry;
    }

    private void unlinkEntry(Entry<T> target) {
        int bucket = target.hash & (buckets.length - 1);
        Entry<T> previous = null;
        for (Entry<T> entry = buckets[bucket]; entry != null; entry = entry.next) {
            if (entry == target) {
                if (previous == null) {
                    buckets[bucket] = entry.next;
                } else {
                    previous.next = entry.next;
                }
                entry.next = null;
                return;
            }
            previous = entry;
        }
    }

    private T removeAtLocked(int index) {
        T previous = (T) elements[index];
        unlinkEntry(positions[index]);

        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            positions[i] = positions[i + 1];
            positions[i].index = i;
        }

        size--;
        elements[size] = null;
        positions[size] = null;
        return previous;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            int hash = spreadHash(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }

            Entry<T> entry = new Entry<>(item, hash, size);
            ensureArrayCapacity(size + 1);
            ensureHashCapacity(size + 1);
            elements[size] = item;
            positions[size] = entry;
            size++;
            linkEntry(entry);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            checkInsertIndex(index, size);
            int hash = spreadHash(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }

            Entry<T> entry = new Entry<>(item, hash, index);
            ensureArrayCapacity(size + 1);
            ensureHashCapacity(size + 1);

            for (int i = size; i > index; i--) {
                elements[i] = elements[i - 1];
                positions[i] = positions[i - 1];
                positions[i].index = i;
            }

            elements[index] = item;
            positions[index] = entry;
            size++;
            linkEntry(entry);
            return true;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkIndex(index, size);
            return (T) elements[index];
        }
    }

    public T set(int index, T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            checkIndex(index, size);
            int hash = spreadHash(item.hashCode());
            Entry<T> existing = findEntry(item, hash);
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("An equal item already exists at index " + existing.index);
            }

            T previous = (T) elements[index];
            Entry<T> current = positions[index];
            if (existing == current) {
                current.item = item;
                elements[index] = item;
            } else {
                Entry<T> replacement = new Entry<>(item, hash, index);
                unlinkEntry(current);
                elements[index] = item;
                positions[index] = replacement;
                linkEntry(replacement);
            }
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkIndex(index, size);
            return removeAtLocked(index);
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            int hash = spreadHash(item.hashCode());
            Entry<T> entry = findEntry(item, hash);
            if (entry == null) {
                return false;
            }
            removeAtLocked(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            int hash = spreadHash(item.hashCode());
            return findEntry(item, hash) != null;
        }
    }

    public int indexOf(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            int hash = spreadHash(item.hashCode());
            Entry<T> entry = findEntry(item, hash);
            return entry == null ? -1 : entry.index;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[size];
            for (int i = 0; i < size; i++) {
                result[i] = elements[i];
            }
            return result;
        }
    }
}
