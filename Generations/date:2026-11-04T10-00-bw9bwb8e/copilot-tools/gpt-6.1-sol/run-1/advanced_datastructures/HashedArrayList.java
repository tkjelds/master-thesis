/**
 * A thread-safe, ordered, duplicate-free list backed by a resizable array
 * and a separate-chaining hash index.
 *
 * Items must retain stable equality and hash codes while stored.
 * Operations never wait for capacity or contents. Invalid indices throw
 * IndexOutOfBoundsException; exhausted storage throws OutOfMemoryError.
 */
@HashedArrayList.ThreadSafe
public final class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_BUCKET_CAPACITY = 1 << 30;

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

    // Each retained entry occurs once in each structure.
    private Entry[] elements = new Entry[INITIAL_CAPACITY];
    private Entry[] buckets = new Entry[INITIAL_CAPACITY];
    private int threshold = INITIAL_CAPACITY * 3 / 4;
    private int size;

    public HashedArrayList() {
    }

    public boolean add(T item) {
        synchronized (lock) {
            return insertInternal(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            checkPositionIndex(index);
            return insertInternal(index, item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return valueOf(elements[index]);
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            checkElementIndex(index);
            requireNonNull(item);

            int hash = hashOf(item);
            Entry existing = find(item, hash);
            Entry current = elements[index];

            if (existing != null && existing != current) {
                throw new IllegalArgumentException(
                        "An equal item exists at another index");
            }

            T previous = valueOf(current);
            if (existing == current) {
                current.item = item;
                return previous;
            }

            unlink(current);
            current.item = item;
            current.hash = hash;
            link(current);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            Entry entry = elements[index];
            T previous = valueOf(entry);
            removeEntry(entry);
            return previous;
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
                result[i] = elements[i].item;
            }
            return result;
        }
    }

    // All state-accessing helpers are called only while holding lock.
    private boolean insertInternal(int index, T item) {
        requireNonNull(item);
        int hash = hashOf(item);
        if (find(item, hash) != null) {
            return false;
        }

        Entry entry = new Entry(item, hash, index);
        ensureRoom();

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

    private void removeEntry(Entry entry) {
        unlink(entry);

        int newSize = size - 1;
        for (int i = entry.index; i < newSize; i++) {
            Entry shifted = elements[i + 1];
            elements[i] = shifted;
            shifted.index = i;
        }

        elements[newSize] = null;
        size = newSize;
    }

    private Entry find(Object item, int hash) {
        int bucket = hash & (buckets.length - 1);
        for (Entry entry = buckets[bucket];
                entry != null; entry = entry.next) {
            if (entry.hash == hash
                    && (item == entry.item || item.equals(entry.item))) {
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

        while (current != target) {
            if (current == null) {
                throw new IllegalStateException("Corrupted hash index");
            }
            previous = current;
            current = current.next;
        }

        if (previous == null) {
            buckets[bucket] = target.next;
        } else {
            previous.next = target.next;
        }
        target.next = null;
    }

    private void ensureRoom() {
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Maximum list size reached");
        }

        Entry[] newElements = elements;
        if (size == elements.length) {
            int capacity = elements.length <= Integer.MAX_VALUE / 2
                    ? elements.length * 2
                    : Integer.MAX_VALUE;
            newElements = new Entry[capacity];
            for (int i = 0; i < size; i++) {
                newElements[i] = elements[i];
            }
        }

        Entry[] newBuckets = null;
        if (size >= threshold && buckets.length < MAX_BUCKET_CAPACITY) {
            newBuckets = new Entry[buckets.length * 2];
        }

        // Finish allocations before modifying any existing hash chains.
        if (newBuckets != null) {
            for (int i = 0; i < size; i++) {
                Entry entry = elements[i];
                int bucket = entry.hash & (newBuckets.length - 1);
                entry.next = newBuckets[bucket];
                newBuckets[bucket] = entry;
            }

            buckets = newBuckets;
            threshold = buckets.length == MAX_BUCKET_CAPACITY
                    ? Integer.MAX_VALUE
                    : buckets.length - buckets.length / 4;
        }

        elements = newElements;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", size: " + size);
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
