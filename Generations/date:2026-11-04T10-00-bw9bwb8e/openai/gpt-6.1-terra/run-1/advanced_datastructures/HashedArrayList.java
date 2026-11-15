@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_TABLE_CAPACITY = 1 << 30;

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
    private Entry[] elements = new Entry[INITIAL_CAPACITY];
    private Entry[] table = new Entry[INITIAL_CAPACITY];
    private int size;

    // Operations do not wait for capacity or elements; only monitor acquisition
    // can block. An invalid positional operation throws immediately.
    public boolean add(T item) {
        synchronized (lock) {
            requireItem(item);
            return insertInternal(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException();
            }
            return insertInternal(index, item);
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return (T) elements[index].item;
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkIndex(index);

            int hash = hash(item);
            Entry existing = find(item, hash);
            Entry current = elements[index];

            if (existing != null && existing != current) {
                throw new IllegalArgumentException("Duplicate item");
            }

            T previous = (T) current.item;
            if (existing == current) {
                current.item = item;
            } else {
                unlink(current);
                current.item = item;
                current.hash = hash;
                link(current);
            }
            return previous;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (lock) {
            checkIndex(index);
            return (T) removeInternal(index);
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireItem(item);
            Entry entry = find(item, hash(item));
            if (entry == null) {
                return false;
            }
            removeInternal(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireItem(item);
            return find(item, hash(item)) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (lock) {
            requireItem(item);
            Entry entry = find(item, hash(item));
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
                result[i] = elements[i].item;
            }
            return result;
        }
    }

    private boolean insertInternal(int index, T item) {
        int hash = hash(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == 2147483647) {
            throw new OutOfMemoryError("List too large");
        }

        Entry entry = new Entry(item, hash, index);
        ensureArrayCapacity(size + 1);
        ensureTableCapacity(size + 1);

        for (int i = size; i > index; i--) {
            Entry shifted = elements[i - 1];
            elements[i] = shifted;
            shifted.index = i;
        }
        elements[index] = entry;
        link(entry);
        size++;
        return true;
    }

    private Object removeInternal(int index) {
        Entry removed = elements[index];
        unlink(removed);

        int last = size - 1;
        for (int i = index; i < last; i++) {
            Entry shifted = elements[i + 1];
            elements[i] = shifted;
            shifted.index = i;
        }
        elements[last] = null;
        size = last;
        return removed.item;
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
        Entry previous = null;
        Entry current = table[bucket];
        while (current != entry) {
            previous = current;
            current = current.next;
        }
        if (previous == null) {
            table[bucket] = current.next;
        } else {
            previous.next = current.next;
        }
        current.next = null;
    }

    private void ensureArrayCapacity(int required) {
        if (required <= elements.length) {
            return;
        }
        long grown = (long) elements.length * 2;
        if (grown < required) {
            grown = required;
        }
        if (grown > 2147483647L) {
            grown = 2147483647L;
        }
        Entry[] replacement = new Entry[(int) grown];
        for (int i = 0; i < size; i++) {
            replacement[i] = elements[i];
        }
        elements = replacement;
    }

    private void ensureTableCapacity(int required) {
        int capacity = table.length;
        if (capacity == MAX_TABLE_CAPACITY
                || required <= capacity - capacity / 4) {
            return;
        }

        Entry[] replacement = new Entry[capacity * 2];
        for (int i = 0; i < table.length; i++) {
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

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
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
}
