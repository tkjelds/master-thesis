@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

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

    private final Object lock = new Object();
    private Entry[] entries = new Entry[8];
    private Entry[] buckets = new Entry[16];
    private int size;

    // Operations do not wait for capacity or for an item to become available.
    public boolean add(T item) {
        synchronized (lock) {
            return insertInternal(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            return insertInternal(index, item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return valueOf(entries[index]);
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            requireNonNull(item);
            checkElementIndex(index);

            int hash = hashOf(item);
            Entry existing = find(item, hash);
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("Item already exists");
            }

            Entry entry = entries[index];
            T previous = valueOf(entry);
            unlink(entry);
            entry.item = item;
            entry.hash = hash;
            link(entry);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return removeEntry(entries[index]);
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireNonNull(item);
            Entry entry = find(item, hashOf(item));
            if (entry == null) {
                return false;
            }
            removeEntry(entry);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireNonNull(item);
            return find(item, hashOf(item)) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (lock) {
            requireNonNull(item);
            Entry entry = find(item, hashOf(item));
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
                result[i] = entries[i].item;
            }
            return result;
        }
    }

    private boolean insertInternal(int index, T item) {
        requireNonNull(item);
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }

        int hash = hashOf(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List is too large");
        }

        Entry entry = new Entry(item, hash, index);
        ensureCapacity(size + 1);

        for (int i = size; i > index; i--) {
            entries[i] = entries[i - 1];
            entries[i].index = i;
        }
        entries[index] = entry;
        link(entry);
        size++;
        return true;
    }

    private T removeEntry(Entry entry) {
        T previous = valueOf(entry);
        int index = entry.index;
        unlink(entry);

        for (int i = index; i < size - 1; i++) {
            entries[i] = entries[i + 1];
            entries[i].index = i;
        }
        entries[size - 1] = null;
        size--;
        return previous;
    }

    private void ensureCapacity(int required) {
        Entry[] newEntries = entries;
        Entry[] newBuckets = buckets;

        if (required > entries.length) {
            long grown = (long) entries.length + (entries.length >> 1) + 1;
            int capacity = (int) (grown > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : grown);
            if (capacity < required) {
                capacity = required;
            }
            newEntries = new Entry[capacity];
            for (int i = 0; i < size; i++) {
                newEntries[i] = entries[i];
            }
        }

        if (required > buckets.length - (buckets.length >> 2)
                && buckets.length < (1 << 30)) {
            newBuckets = new Entry[buckets.length << 1];
        }

        if (newBuckets != buckets) {
            for (int i = 0; i < size; i++) {
                Entry entry = entries[i];
                int bucket = entry.hash & (newBuckets.length - 1);
                entry.next = newBuckets[bucket];
                newBuckets[bucket] = entry;
            }
        }

        entries = newEntries;
        buckets = newBuckets;
    }

    private Entry find(Object item, int hash) {
        int bucket = hash & (buckets.length - 1);
        for (Entry entry = buckets[bucket]; entry != null; entry = entry.next) {
            if (entry.hash == hash && entry.item.equals(item)) {
                return entry;
            }
        }
        return null;
    }

    private void link(Entry entry) {
        int bucket = entry.hash & (buckets.length - 1);
        entry.next = buckets[bucket];
        buckets[bucket] = entry;
    }

    private void unlink(Entry entry) {
        int bucket = entry.hash & (buckets.length - 1);
        Entry current = buckets[bucket];
        Entry previous = null;
        while (current != entry) {
            previous = current;
            current = current.next;
        }
        if (previous == null) {
            buckets[bucket] = current.next;
        } else {
            previous.next = current.next;
        }
        current.next = null;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
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

    @SuppressWarnings("unchecked")
    private T valueOf(Entry entry) {
        return (T) entry.item;
    }
}
