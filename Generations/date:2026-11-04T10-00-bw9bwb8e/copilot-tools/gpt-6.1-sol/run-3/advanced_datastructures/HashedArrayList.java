@HashedArrayList.ThreadSafe
public final class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_TABLE_CAPACITY = 1 << 30;
    private static final int MAX_ARRAY_CAPACITY = 2147483647;

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

    private final Object monitor = new Object();
    private Entry[] sequence = new Entry[INITIAL_CAPACITY];
    private Entry[] table = new Entry[INITIAL_CAPACITY];
    private int size;

    /*
     * Operations acquire one monitor and never wait for collection contents
     * to change. Storage grows as needed; allocation failure is reported by
     * the JVM. Empty positional operations throw IndexOutOfBoundsException.
     */
    public HashedArrayList() {
    }

    public boolean add(T item) {
        synchronized (monitor) {
            return insertLocked(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (monitor) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException();
            }
            return insertLocked(index, item);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            checkIndex(index);
            return itemOf(sequence[index]);
        }
    }

    public T set(int index, T item) {
        synchronized (monitor) {
            checkIndex(index);
            int hash = checkedHash(item);
            Entry previous = sequence[index];
            Entry existing = find(item, hash);

            if (existing != null && existing != previous) {
                throw new IllegalArgumentException(
                        "An equal item exists at another index");
            }

            Entry replacement = new Entry(item, hash, index);
            unlink(previous);
            link(replacement);
            sequence[index] = replacement;
            return itemOf(previous);
        }
    }

    public T removeAt(int index) {
        synchronized (monitor) {
            checkIndex(index);
            return removeAtLocked(index);
        }
    }

    public boolean remove(T item) {
        synchronized (monitor) {
            int hash = checkedHash(item);
            Entry entry = find(item, hash);
            if (entry == null) {
                return false;
            }
            removeAtLocked(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (monitor) {
            return find(item, checkedHash(item)) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (monitor) {
            Entry entry = find(item, checkedHash(item));
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
            Object[] result = new Object[size];
            for (int i = 0; i < size; i++) {
                result[i] = sequence[i].item;
            }
            return result;
        }
    }

    private boolean insertLocked(int index, T item) {
        int hash = checkedHash(item);
        if (find(item, hash) != null) {
            return false;
        }

        Entry entry = new Entry(item, hash, index);
        ensureCapacityForInsert();

        for (int i = size; i > index; i--) {
            Entry shifted = sequence[i - 1];
            sequence[i] = shifted;
            shifted.index = i;
        }

        sequence[index] = entry;
        link(entry);
        size++;
        return true;
    }

    private T removeAtLocked(int index) {
        Entry removed = sequence[index];
        unlink(removed);

        int newSize = size - 1;
        for (int i = index; i < newSize; i++) {
            Entry shifted = sequence[i + 1];
            sequence[i] = shifted;
            shifted.index = i;
        }

        sequence[newSize] = null;
        size = newSize;
        return itemOf(removed);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private static int checkedHash(Object item) {
        if (item == null) {
            throw new NullPointerException();
        }
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private Entry find(Object item, int hash) {
        int bucket = hash & (table.length - 1);
        for (Entry entry = table[bucket]; entry != null; entry = entry.next) {
            if (entry.hash == hash
                    && (item == entry.item || item.equals(entry.item))) {
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

    private void unlink(Entry target) {
        int bucket = target.hash & (table.length - 1);
        Entry previous = null;
        Entry current = table[bucket];

        while (current != target) {
            if (current == null) {
                throw new IllegalStateException("Missing hash index entry");
            }
            previous = current;
            current = current.next;
        }

        if (previous == null) {
            table[bucket] = target.next;
        } else {
            previous.next = target.next;
        }
        target.next = null;
    }

    private void ensureCapacityForInsert() {
        if (size == MAX_ARRAY_CAPACITY) {
            throw new OutOfMemoryError("Maximum list size reached");
        }

        Entry[] expandedSequence = sequence;
        if (size == sequence.length) {
            int capacity = sequence.length <= MAX_ARRAY_CAPACITY / 2
                    ? sequence.length * 2
                    : MAX_ARRAY_CAPACITY;
            expandedSequence = new Entry[capacity];
            for (int i = 0; i < size; i++) {
                expandedSequence[i] = sequence[i];
            }
        }

        Entry[] expandedTable = table;
        int threshold = table.length - table.length / 4;
        if (size + 1 > threshold && table.length < MAX_TABLE_CAPACITY) {
            expandedTable = new Entry[table.length * 2];
        }

        // Allocate all new storage before changing any existing bucket links.
        if (expandedTable != table) {
            for (int i = 0; i < size; i++) {
                Entry entry = sequence[i];
                int bucket = entry.hash & (expandedTable.length - 1);
                entry.next = expandedTable[bucket];
                expandedTable[bucket] = entry;
            }
            table = expandedTable;
        }
        sequence = expandedSequence;
    }

    @SuppressWarnings("unchecked")
    private T itemOf(Entry entry) {
        return (T) entry.item;
    }
}
