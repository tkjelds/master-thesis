@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 8;
    private static final int INITIAL_TABLE_CAPACITY = 16;

    private final Object lock = new Object();
    private Object[] elements = new Object[INITIAL_CAPACITY];
    private Entry[] positions = new Entry[INITIAL_CAPACITY];
    private Entry[] buckets = new Entry[INITIAL_TABLE_CAPACITY];
    private int size;

    private static final class Entry {
        Object item;
        int index;
        final int hash;
        Entry next;

        Entry(Object item, int index, int hash) {
            this.item = item;
            this.index = index;
            this.hash = hash;
        }
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return insertAt(size, item);
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkPositionIndex(index);
            return insertAt(index, item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return elementAt(index);
        }
    }

    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkElementIndex(index);

            int hash = spread(item.hashCode());
            Entry existing = find(item, hash);
            Entry oldEntry = positions[index];
            if (existing != null && existing != oldEntry) {
                throw new IllegalArgumentException("An equal item is already present");
            }

            T previous = elementAt(index);
            if (existing == oldEntry) {
                elements[index] = item;
                oldEntry.item = item;
            } else {
                Entry replacement = new Entry(item, index, hash);
                unlink(oldEntry);
                elements[index] = item;
                positions[index] = replacement;
                link(replacement);
            }
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
        requireNonNull(item);
        synchronized (lock) {
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
        requireNonNull(item);
        synchronized (lock) {
            int hash = spread(item.hashCode());
            return find(item, hash) != null;
        }
    }

    public int indexOf(T item) {
        requireNonNull(item);
        synchronized (lock) {
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

    private boolean insertAt(int index, T item) {
        int hash = spread(item.hashCode());
        if (find(item, hash) != null) {
            return false;
        }

        ensureElementCapacity(size + 1);
        ensureTableCapacity(size + 1);

        Entry entry = new Entry(item, index, hash);
        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
            positions[i] = positions[i - 1];
            positions[i].index = i;
        }
        elements[index] = item;
        positions[index] = entry;
        link(entry);
        size++;
        return true;
    }

    private T removeAtInternal(int index) {
        T removed = elementAt(index);
        unlink(positions[index]);

        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            positions[i] = positions[i + 1];
            positions[i].index = i;
        }

        size--;
        elements[size] = null;
        positions[size] = null;
        return removed;
    }

    private Entry find(Object item, int hash) {
        for (Entry entry = buckets[hash & (buckets.length - 1)];
             entry != null;
             entry = entry.next) {
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

    private void unlink(Entry target) {
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

    private void ensureElementCapacity(int minimumCapacity) {
        if (minimumCapacity <= elements.length) {
            return;
        }

        int newCapacity = elements.length;
        while (newCapacity < minimumCapacity) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = minimumCapacity;
                break;
            }
            newCapacity *= 2;
        }

        Object[] newElements = new Object[newCapacity];
        Entry[] newPositions = new Entry[newCapacity];
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[i];
            newPositions[i] = positions[i];
        }
        elements = newElements;
        positions = newPositions;
    }

    private void ensureTableCapacity(int minimumSize) {
        if (minimumSize <= buckets.length - buckets.length / 4
                || buckets.length > Integer.MAX_VALUE / 2) {
            return;
        }

        Entry[] newBuckets = new Entry[buckets.length * 2];
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

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException();
        }
    }

    @SuppressWarnings("unchecked")
    private T elementAt(int index) {
        return (T) elements[index];
    }
}
