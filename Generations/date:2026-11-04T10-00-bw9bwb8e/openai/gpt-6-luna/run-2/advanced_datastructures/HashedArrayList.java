@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 8;

    private Object[] elements;
    private Entry[] positions;
    private Entry[] buckets;
    private int size;

    private static final class Entry {
        final int hash;
        int index;
        Object item;
        Entry next;

        Entry(Object item, int hash, int index) {
            this.item = item;
            this.hash = hash;
            this.index = index;
        }
    }

    public HashedArrayList() {
        elements = new Object[INITIAL_CAPACITY];
        positions = new Entry[INITIAL_CAPACITY];
        buckets = new Entry[INITIAL_CAPACITY];
    }

    public boolean add(T item) {
        synchronized (this) {
            requireNonNull(item);
            return insertInternal(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (this) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException(
                        "index: " + index + ", size: " + size);
            }
            requireNonNull(item);
            return insertInternal(index, item);
        }
    }

    public T get(int index) {
        synchronized (this) {
            checkElementIndex(index);
            return elementAt(index);
        }
    }

    public T set(int index, T item) {
        synchronized (this) {
            checkElementIndex(index);
            requireNonNull(item);

            int hash = hashOf(item);
            Entry existing = findEntry(item, hash);
            Entry previousEntry = positions[index];
            T previous = elementAt(index);

            if (existing != null) {
                if (existing.index != index) {
                    throw new IllegalArgumentException(
                            "An equal item is already at index " + existing.index);
                }
                existing.item = item;
                elements[index] = item;
                return previous;
            }

            Entry replacement = new Entry(item, hash, index);
            unlinkEntry(previousEntry);
            addToBucket(replacement, buckets);
            positions[index] = replacement;
            elements[index] = item;
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (this) {
            checkElementIndex(index);
            T removed = elementAt(index);
            removeEntryAt(index);
            return removed;
        }
    }

    public boolean remove(T item) {
        synchronized (this) {
            requireNonNull(item);
            Entry entry = findEntry(item, hashOf(item));
            if (entry == null) {
                return false;
            }
            removeEntryAt(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (this) {
            requireNonNull(item);
            return findEntry(item, hashOf(item)) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (this) {
            requireNonNull(item);
            Entry entry = findEntry(item, hashOf(item));
            return entry == null ? -1 : entry.index;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = elements[i];
            }
            return copy;
        }
    }

    private boolean insertInternal(int index, T item) {
        int hash = hashOf(item);
        if (findEntry(item, hash) != null) {
            return false;
        }

        Entry inserted = new Entry(item, hash, index);

        Object[] grownElements = null;
        Entry[] grownPositions = null;
        if (size == elements.length) {
            int newCapacity = doubledCapacity(elements.length);
            grownElements = new Object[newCapacity];
            grownPositions = new Entry[newCapacity];
            for (int i = 0; i < size; i++) {
                grownElements[i] = elements[i];
                grownPositions[i] = positions[i];
            }
        }

        Entry[] grownBuckets = null;
        if (size + 1 > buckets.length - buckets.length / 4) {
            int newCapacity = doubledCapacity(buckets.length);
            grownBuckets = new Entry[newCapacity];
        }

        if (grownBuckets != null) {
            for (int i = 0; i < size; i++) {
                Entry entry = positions[i];
                int bucket = entry.hash & (grownBuckets.length - 1);
                entry.next = grownBuckets[bucket];
                grownBuckets[bucket] = entry;
            }
            buckets = grownBuckets;
        }

        if (grownElements != null) {
            elements = grownElements;
            positions = grownPositions;
        }

        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
            Entry shifted = positions[i - 1];
            positions[i] = shifted;
            shifted.index = i;
        }

        elements[index] = item;
        inserted.index = index;
        positions[index] = inserted;
        addToBucket(inserted, buckets);
        size++;
        return true;
    }

    private void removeEntryAt(int index) {
        Entry removed = positions[index];
        unlinkEntry(removed);

        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            Entry shifted = positions[i + 1];
            positions[i] = shifted;
            shifted.index = i;
        }

        size--;
        elements[size] = null;
        positions[size] = null;
    }

    private Entry findEntry(Object item, int hash) {
        int bucket = hash & (buckets.length - 1);
        for (Entry entry = buckets[bucket]; entry != null; entry = entry.next) {
            if (entry.hash == hash && item.equals(entry.item)) {
                return entry;
            }
        }
        return null;
    }

    private void addToBucket(Entry entry, Entry[] table) {
        int bucket = entry.hash & (table.length - 1);
        entry.next = table[bucket];
        table[bucket] = entry;
    }

    private void unlinkEntry(Entry target) {
        int bucket = target.hash & (buckets.length - 1);
        Entry previous = null;
        Entry current = buckets[bucket];

        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    buckets[bucket] = current.next;
                } else {
                    previous.next = current.next;
                }
                current.next = null;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "index: " + index + ", size: " + size);
        }
    }

    @SuppressWarnings("unchecked")
    private T elementAt(int index) {
        return (T) elements[index];
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int hashOf(Object item) {
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int doubledCapacity(int current) {
        if (current >= (1 << 30)) {
            throw new OutOfMemoryError("Required array capacity is too large");
        }
        return current << 1;
    }
}
