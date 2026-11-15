@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    private Object[] elements = new Object[8];
    private Entry[] entriesByPosition = new Entry[8];
    private Entry[] buckets = new Entry[16];
    private int size;

    private static final class Entry {
        final Object item;
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
        synchronized (lock) {
            requireNonNull(item);
            int hash = spread(item.hashCode());
            if (find(item, hash) != null) {
                return false;
            }

            Entry entry = new Entry(item, hash, size);
            ensureArrayCapacity(size + 1);
            ensureTableCapacity(size + 1);
            elements[size] = item;
            entriesByPosition[size] = entry;
            addEntry(entry);
            size++;
            return true;
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            checkPositionIndex(index);
            requireNonNull(item);
            int hash = spread(item.hashCode());
            if (find(item, hash) != null) {
                return false;
            }

            Entry entry = new Entry(item, hash, index);
            ensureArrayCapacity(size + 1);
            ensureTableCapacity(size + 1);

            for (int i = size; i > index; i--) {
                elements[i] = elements[i - 1];
                Entry shifted = entriesByPosition[i - 1];
                entriesByPosition[i] = shifted;
                shifted.index = i;
            }

            elements[index] = item;
            entriesByPosition[index] = entry;
            addEntry(entry);
            size++;
            return true;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return elementAt(index);
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            checkElementIndex(index);
            requireNonNull(item);

            int hash = spread(item.hashCode());
            Entry existing = find(item, hash);
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("An equal item already exists at another index");
            }

            Entry replacement = new Entry(item, hash, index);
            T previous = elementAt(index);
            removeEntry(entriesByPosition[index]);
            elements[index] = item;
            entriesByPosition[index] = replacement;
            addEntry(replacement);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return removeAtInternal(index);
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireNonNull(item);
            int hash = spread(item.hashCode());
            Entry entry = find(item, hash);
            if (entry == null) {
                return false;
            }
            removeAtInternal(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireNonNull(item);
            int hash = spread(item.hashCode());
            return find(item, hash) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (lock) {
            requireNonNull(item);
            int hash = spread(item.hashCode());
            Entry entry = find(item, hash);
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

    private T removeAtInternal(int index) {
        T removed = elementAt(index);
        removeEntry(entriesByPosition[index]);

        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            Entry shifted = entriesByPosition[i + 1];
            entriesByPosition[i] = shifted;
            shifted.index = i;
        }

        size--;
        elements[size] = null;
        entriesByPosition[size] = null;
        return removed;
    }

    private Entry find(Object item, int hash) {
        for (Entry entry = buckets[hash & (buckets.length - 1)];
             entry != null;
             entry = entry.next) {
            if (entry.hash == hash && item.equals(entry.item)) {
                return entry;
            }
        }
        return null;
    }

    private void addEntry(Entry entry) {
        int bucket = entry.hash & (buckets.length - 1);
        entry.next = buckets[bucket];
        buckets[bucket] = entry;
    }

    private void removeEntry(Entry target) {
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

        long candidate = (long) elements.length + elements.length / 2L + 1L;
        if (candidate < required) {
            candidate = required;
        }
        if (candidate > Integer.MAX_VALUE) {
            candidate = Integer.MAX_VALUE;
        }

        Object[] newElements = new Object[(int) candidate];
        Entry[] newEntries = new Entry[(int) candidate];
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[i];
            newEntries[i] = entriesByPosition[i];
        }
        elements = newElements;
        entriesByPosition = newEntries;
    }

    private void ensureTableCapacity(int required) {
        if ((long) required <= buckets.length - buckets.length / 4L
                || buckets.length >= (1 << 30)) {
            return;
        }

        Entry[] newBuckets = new Entry[buckets.length << 1];
        for (Entry head : buckets) {
            Entry entry = head;
            while (entry != null) {
                Entry next = entry.next;
                int bucket = entry.hash & (newBuckets.length - 1);
                entry.next = newBuckets[bucket];
                newBuckets[bucket] = entry;
                entry = next;
            }
        }
        buckets = newBuckets;
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

    @SuppressWarnings("unchecked")
    private T elementAt(int index) {
        return (T) elements[index];
    }
}
