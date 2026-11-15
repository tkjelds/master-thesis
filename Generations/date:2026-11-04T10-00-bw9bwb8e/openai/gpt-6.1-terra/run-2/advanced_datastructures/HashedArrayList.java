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
    private Entry[] sequence = new Entry[8];
    private Entry[] table = new Entry[16];
    private int count;

    // Operations do not wait for capacity or items. Growth is limited by memory.
    public boolean add(T item) {
        requireItem(item);
        synchronized (lock) {
            return insertInternal(count, item);
        }
    }

    public boolean insert(int index, T item) {
        requireItem(item);
        synchronized (lock) {
            checkInsertionIndex(index);
            return insertInternal(index, item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return castItem(sequence[index].item);
        }
    }

    public T set(int index, T item) {
        requireItem(item);
        synchronized (lock) {
            checkElementIndex(index);

            int hash = hash(item);
            Entry existing = find(item, hash);
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("Item already exists");
            }

            Entry entry = sequence[index];
            T previous = castItem(entry.item);
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
            return removeEntry(sequence[index]);
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (lock) {
            Entry entry = find(item, hash(item));
            if (entry == null) {
                return false;
            }
            removeEntry(entry);
            return true;
        }
    }

    public boolean contains(T item) {
        requireItem(item);
        synchronized (lock) {
            return find(item, hash(item)) != null;
        }
    }

    public int indexOf(T item) {
        requireItem(item);
        synchronized (lock) {
            Entry entry = find(item, hash(item));
            return entry == null ? -1 : entry.index;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[count];
            for (int i = 0; i < count; i++) {
                result[i] = sequence[i].item;
            }
            return result;
        }
    }

    private boolean insertInternal(int index, T item) {
        int hash = hash(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (count == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List is too large");
        }

        int newSize = count + 1;
        ensureSequenceCapacity(newSize);
        ensureTableCapacity(newSize);
        Entry entry = new Entry(item, hash, index);

        for (int i = count; i > index; i--) {
            Entry shifted = sequence[i - 1];
            sequence[i] = shifted;
            shifted.index = i;
        }
        sequence[index] = entry;
        link(entry);
        count = newSize;
        return true;
    }

    private T removeEntry(Entry entry) {
        T previous = castItem(entry.item);
        int index = entry.index;
        unlink(entry);

        for (int i = index; i < count - 1; i++) {
            Entry shifted = sequence[i + 1];
            sequence[i] = shifted;
            shifted.index = i;
        }
        sequence[--count] = null;
        return previous;
    }

    private Entry find(Object item, int hash) {
        int bucket = hash & (table.length - 1);
        for (Entry entry = table[bucket]; entry != null; entry = entry.next) {
            if (entry.hash == hash && item.equals(entry.item)) {
                return entry;
            }
        }
        return null;
    }

    private void link(Entry entry) {
        int bucket = entry.hash & (table.length - 1);
        entry.next = table[bucket];
        table[bucket] = entry;
    }

    private void unlink(Entry entry) {
        int bucket = entry.hash & (table.length - 1);
        Entry current = table[bucket];
        Entry previous = null;

        while (current != entry) {
            previous = current;
            current = current.next;
        }
        if (previous == null) {
            table[bucket] = entry.next;
        } else {
            previous.next = entry.next;
        }
        entry.next = null;
    }

    private void ensureSequenceCapacity(int required) {
        if (required <= sequence.length) {
            return;
        }

        long grown = (long) sequence.length + (sequence.length >> 1) + 1;
        if (grown < required) {
            grown = required;
        }
        if (grown > Integer.MAX_VALUE) {
            grown = Integer.MAX_VALUE;
        }

        Entry[] replacement = new Entry[(int) grown];
        for (int i = 0; i < count; i++) {
            replacement[i] = sequence[i];
        }
        sequence = replacement;
    }

    private void ensureTableCapacity(int required) {
        int capacity = table.length;
        int threshold = capacity - (capacity >>> 2);
        if (required <= threshold || capacity == (1 << 30)) {
            return;
        }

        Entry[] replacement = new Entry[capacity << 1];
        for (int i = 0; i < capacity; i++) {
            Entry entry = table[i];
            while (entry != null) {
                Entry next = entry.next;
                int bucket = entry.hash & (replacement.length - 1);
                entry.next = replacement[bucket];
                replacement[bucket] = entry;
                entry = next;
            }
        }
        table = replacement;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void checkInsertionIndex(int index) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException();
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException();
        }
    }

    private static int hash(Object item) {
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    @SuppressWarnings("unchecked")
    private T castItem(Object item) {
        return (T) item;
    }
}
