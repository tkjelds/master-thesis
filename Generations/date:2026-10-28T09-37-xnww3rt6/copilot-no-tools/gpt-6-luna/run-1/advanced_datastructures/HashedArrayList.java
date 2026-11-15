@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_ARRAY_CAPACITY = 8;
    private static final int INITIAL_TABLE_CAPACITY = 16;

    private static final class Entry {
        Object item;
        int hash;
        int index;
        Entry next;

        Entry(Object item, int hash, int index) {
            this.item = item;
            this.hash = hash;
            this.index = index;
        }
    }

    private final Object mutex = new Object();
    private Entry[] elements = new Entry[INITIAL_ARRAY_CAPACITY];
    private Entry[] buckets = new Entry[INITIAL_TABLE_CAPACITY];
    private int size;

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            return insertLocked(size, item);
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (mutex) {
            checkPositionIndex(index);
            return insertLocked(index, item);
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (mutex) {
            checkElementIndex(index);
            return (T) elements[index].item;
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (mutex) {
            checkElementIndex(index);

            Entry current = elements[index];
            int hash = item.hashCode();
            Entry matching = findEntry(item, hash);

            if (matching != null && matching != current) {
                throw new IllegalArgumentException("An equal item already exists");
            }

            Object previous = current.item;
            if (matching == current) {
                current.item = item;
                return (T) previous;
            }

            unlinkEntry(current);
            current.item = item;
            current.hash = hash;
            linkEntry(current);
            return (T) previous;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (mutex) {
            checkElementIndex(index);
            return (T) removeAtLocked(index);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            Entry entry = findEntry(item, item.hashCode());
            if (entry == null) {
                return false;
            }
            removeAtLocked(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            return findEntry(item, item.hashCode()) != null;
        }
    }

    public int indexOf(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            Entry entry = findEntry(item, item.hashCode());
            return entry == null ? -1 : entry.index;
        }
    }

    public int size() {
        synchronized (mutex) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (mutex) {
            Object[] result = new Object[size];
            for (int i = 0; i < size; i++) {
                result[i] = elements[i].item;
            }
            return result;
        }
    }

    private boolean insertLocked(int index, T item) {
        int hash = item.hashCode();
        if (findEntry(item, hash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List too large");
        }

        int requiredCapacity = size + 1;
        ensureArrayCapacity(requiredCapacity);

        Entry entry = new Entry(item, hash, index);
        ensureTableCapacityForOneMore();

        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
            elements[i].index = i;
        }
        elements[index] = entry;
        size++;
        linkEntry(entry);
        return true;
    }

    private Object removeAtLocked(int index) {
        Entry removed = elements[index];
        unlinkEntry(removed);

        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            elements[i].index = i;
        }
        elements[size - 1] = null;
        size--;
        return removed.item;
    }

    private Entry findEntry(Object item, int hash) {
        int bucket = bucketIndex(hash, buckets.length);
        for (Entry entry = buckets[bucket]; entry != null; entry = entry.next) {
            if (entry.hash == hash && entry.item.equals(item)) {
                return entry;
            }
        }
        return null;
    }

    private void linkEntry(Entry entry) {
        int bucket = bucketIndex(entry.hash, buckets.length);
        entry.next = buckets[bucket];
        buckets[bucket] = entry;
    }

    private void unlinkEntry(Entry target) {
        int bucket = bucketIndex(target.hash, buckets.length);
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

    private void ensureArrayCapacity(int minimum) {
        if (minimum <= elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1) + 1;
        if (newCapacity < minimum || newCapacity <= oldCapacity) {
            newCapacity = minimum;
        }

        Entry[] expanded = new Entry[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private void ensureTableCapacityForOneMore() {
        int threshold = buckets.length - buckets.length / 4;
        if (size < threshold) {
            return;
        }
        if (buckets.length >= (1 << 30)) {
            throw new OutOfMemoryError("Hash table too large");
        }

        Entry[] expanded = new Entry[buckets.length << 1];
        for (int i = 0; i < buckets.length; i++) {
            Entry entry = buckets[i];
            while (entry != null) {
                Entry next = entry.next;
                int bucket = bucketIndex(entry.hash, expanded.length);
                entry.next = expanded[bucket];
                expanded[bucket] = entry;
                entry = next;
            }
        }
        buckets = expanded;
    }

    private static int bucketIndex(int hash, int capacity) {
        int spreadHash = hash ^ (hash >>> 16);
        return spreadHash & (capacity - 1);
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Size: " + size);
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }
}
