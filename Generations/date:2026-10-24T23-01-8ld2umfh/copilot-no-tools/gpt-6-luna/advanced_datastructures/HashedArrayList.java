@HashedArrayList.ThreadSafe
public final class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_ARRAY_CAPACITY = 8;
    private static final int INITIAL_TABLE_CAPACITY = 16;
    private static final int MAX_ARRAY_CAPACITY = Integer.MAX_VALUE - 8;
    private static final int MAX_TABLE_CAPACITY = 1 << 30;

    private final Object monitor = new Object();
    private Entry[] elements = new Entry[INITIAL_ARRAY_CAPACITY];
    private Entry[] buckets = new Entry[INITIAL_TABLE_CAPACITY];
    private int size;

    private static final class Entry {
        Object item;
        final int hash;
        int index;
        Entry next;

        Entry(Object item, int hash, int index) {
            this.item = item;
            this.hash = hash;
            this.index = index;
        }
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = spread(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            insertLocked(size, item, hash);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (monitor) {
            checkPositionIndex(index);
            int hash = spread(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            insertLocked(index, item, hash);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return (T) elements[index].item;
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (monitor) {
            checkElementIndex(index);

            Entry previous = elements[index];
            int hash = spread(item.hashCode());
            Entry existing = findEntry(item, hash);
            if (existing != null && existing != previous) {
                throw new IllegalArgumentException("Item already exists at another index");
            }

            T oldItem = (T) previous.item;
            if (existing == previous) {
                previous.item = item;
            } else {
                Entry replacement = new Entry(item, hash, index);
                unlinkEntry(previous);
                elements[index] = replacement;
                linkEntry(replacement);
            }
            return oldItem;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return (T) removeAtLocked(index).item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = spread(item.hashCode());
            Entry entry = findEntry(item, hash);
            if (entry == null) {
                return false;
            }
            removeAtLocked(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = spread(item.hashCode());
            return findEntry(item, hash) != null;
        }
    }

    public int indexOf(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = spread(item.hashCode());
            Entry entry = findEntry(item, hash);
            return entry == null ? -1 : entry.index;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = elements[i].item;
            }
            return copy;
        }
    }

    private void insertLocked(int index, T item, int hash) {
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Required list size is too large");
        }

        Entry inserted = new Entry(item, hash, index);
        ensureArrayCapacity(size + 1);
        ensureTableCapacity();

        for (int i = size; i > index; i--) {
            Entry shifted = elements[i - 1];
            elements[i] = shifted;
            shifted.index = i;
        }
        elements[index] = inserted;
        linkEntry(inserted);
        size++;
    }

    private Entry removeAtLocked(int index) {
        Entry removed = elements[index];
        unlinkEntry(removed);

        for (int i = index; i < size - 1; i++) {
            Entry shifted = elements[i + 1];
            elements[i] = shifted;
            shifted.index = i;
        }
        elements[--size] = null;
        removed.index = -1;
        return removed;
    }

    private Entry findEntry(Object item, int hash) {
        for (Entry entry = buckets[hash & (buckets.length - 1)];
             entry != null;
             entry = entry.next) {
            if (entry.hash == hash && entry.item.equals(item)) {
                return entry;
            }
        }
        return null;
    }

    private void linkEntry(Entry entry) {
        int bucket = entry.hash & (buckets.length - 1);
        entry.next = buckets[bucket];
        buckets[bucket] = entry;
    }

    private void unlinkEntry(Entry target) {
        int bucket = target.hash & (buckets.length - 1);
        Entry previous = null;
        Entry current = buckets[bucket];

        while (current != target) {
            previous = current;
            current = current.next;
        }

        if (previous == null) {
            buckets[bucket] = current.next;
        } else {
            previous.next = current.next;
        }
        target.next = null;
    }

    private void ensureArrayCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int newCapacity = elements.length << 1;
        if (newCapacity <= 0 || newCapacity > MAX_ARRAY_CAPACITY) {
            newCapacity = MAX_ARRAY_CAPACITY;
        }
        if (newCapacity < required) {
            newCapacity = required;
        }
        if (newCapacity < 0) {
            throw new OutOfMemoryError("Required list capacity is too large");
        }

        Entry[] replacement = new Entry[newCapacity];
        System.arraycopy(elements, 0, replacement, 0, size);
        elements = replacement;
    }

    private void ensureTableCapacity() {
        int threshold = (buckets.length >>> 1) + (buckets.length >>> 2);
        if (size < threshold) {
            return;
        }
        if (buckets.length >= MAX_TABLE_CAPACITY) {
            throw new OutOfMemoryError("Required hash table capacity is too large");
        }

        Entry[] replacement = new Entry[buckets.length << 1];
        for (Entry bucket : buckets) {
            Entry entry = bucket;
            while (entry != null) {
                Entry next = entry.next;
                int newBucket = entry.hash & (replacement.length - 1);
                entry.next = replacement[newBucket];
                replacement[newBucket] = entry;
                entry = next;
            }
        }
        buckets = replacement;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }
}
