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

    private Entry[] elements = new Entry[INITIAL_ARRAY_CAPACITY];
    private Entry[] buckets = new Entry[INITIAL_TABLE_CAPACITY];
    private int size;

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            return insertAt(size, item);
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            checkPositionIndex(index);
            return insertAt(index, item);
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (this) {
            checkElementIndex(index);
            return (T) elements[index].item;
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            checkElementIndex(index);

            int hash = hash(item);
            Entry current = elements[index];
            Entry matching = find(item, hash);
            if (matching != null && matching != current) {
                throw new IllegalArgumentException();
            }

            T previous = (T) current.item;
            if (matching == current) {
                current.item = item;
                current.hash = hash;
            } else {
                unlinkEntry(current);
                current.item = item;
                current.hash = hash;
                linkEntry(current);
            }
            return previous;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (this) {
            checkElementIndex(index);
            Entry removed = elements[index];
            T item = (T) removed.item;
            removeEntry(removed);
            return item;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            Entry entry = find(item, hash(item));
            if (entry == null) {
                return false;
            }
            removeEntry(entry);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            return find(item, hash(item)) != null;
        }
    }

    public int indexOf(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            Entry entry = find(item, hash(item));
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
            Object[] result = new Object[size];
            for (int i = 0; i < size; i++) {
                result[i] = elements[i].item;
            }
            return result;
        }
    }

    private boolean insertAt(int index, T item) {
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List is too large");
        }

        int hash = hash(item);
        if (find(item, hash) != null) {
            return false;
        }

        Entry added = new Entry(item, hash, index);
        ensureArrayCapacity(size + 1);
        ensureTableCapacity(size + 1);

        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
            elements[i].index = i;
        }
        elements[index] = added;
        linkEntry(added);
        size++;
        return true;
    }

    private void removeEntry(Entry entry) {
        unlinkEntry(entry);

        int index = entry.index;
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            elements[i].index = i;
        }
        elements[size - 1] = null;
        size--;
        entry.index = -1;
    }

    private Entry find(Object item, int hash) {
        for (Entry entry = buckets[bucketIndex(hash, buckets.length)];
                entry != null; entry = entry.next) {
            if (entry.hash == hash && item.equals(entry.item)) {
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
        Entry entry = buckets[bucket];

        while (entry != null) {
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
            entry = entry.next;
        }

        throw new IllegalStateException("Entry not found in hash table");
    }

    private void ensureArrayCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int capacity = elements.length;
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = Integer.MAX_VALUE;
            } else {
                capacity *= 2;
            }
        }

        Entry[] expanded = new Entry[capacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private void ensureTableCapacity(int requiredSize) {
        int threshold = buckets.length - (buckets.length >>> 2);
        if (requiredSize <= threshold) {
            return;
        }
        if (buckets.length >= (1 << 30)) {
            throw new OutOfMemoryError("Hash table is too large");
        }

        Entry[] expanded = new Entry[buckets.length * 2];
        for (Entry bucket : buckets) {
            Entry entry = bucket;
            while (entry != null) {
                Entry next = entry.next;
                int index = bucketIndex(entry.hash, expanded.length);
                entry.next = expanded[index];
                expanded[index] = entry;
                entry = next;
            }
        }
        buckets = expanded;
    }

    private static int bucketIndex(int hash, int length) {
        return hash & (length - 1);
    }

    private static int hash(Object item) {
        int value = item.hashCode();
        return value ^ (value >>> 16);
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
}
