/**
 * A thread-safe, ordered list that retains at most one representative of
 * each equality class. Retained items must have stable equality and hashes.
 *
 * All operations use one private monitor. The list grows as needed and never
 * waits for items or capacity; allocation failures propagate normally.
 */
@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int MAX_BUCKET_CAPACITY = 1 << 30;

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
    private Entry[] elements = new Entry[8];
    private Entry[] buckets = new Entry[16];
    private int size;

    public HashedArrayList() {
    }

    public boolean add(T item) {
        synchronized (monitor) {
            return insertInternal(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (monitor) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return insertInternal(index, item);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return valueOf(elements[index]);
        }
    }

    public T set(int index, T item) {
        synchronized (monitor) {
            checkElementIndex(index);
            int hash = checkedHash(item);
            Entry previous = elements[index];
            Entry existing = find(item, hash);

            if (existing != null && existing != previous) {
                throw new IllegalArgumentException(
                        "An equal item exists at another index");
            }

            Entry replacement = new Entry(item, hash, index);
            unlink(previous);
            link(replacement);
            elements[index] = replacement;
            return valueOf(previous);
        }
    }

    public T removeAt(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return removeInternal(index);
        }
    }

    public boolean remove(T item) {
        synchronized (monitor) {
            int hash = checkedHash(item);
            Entry entry = find(item, hash);
            if (entry == null) {
                return false;
            }
            removeInternal(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (monitor) {
            int hash = checkedHash(item);
            return find(item, hash) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (monitor) {
            int hash = checkedHash(item);
            Entry entry = find(item, hash);
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
                result[i] = elements[i].item;
            }
            return result;
        }
    }

    private boolean insertInternal(int index, T item) {
        int hash = checkedHash(item);
        if (find(item, hash) != null) {
            return false;
        }

        Entry entry = new Entry(item, hash, index);
        ensureInsertionCapacity();

        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
            elements[i].index = i;
        }
        elements[index] = entry;
        link(entry);
        size++;
        return true;
    }

    private T removeInternal(int index) {
        Entry removed = elements[index];
        unlink(removed);

        int newSize = size - 1;
        for (int i = index; i < newSize; i++) {
            elements[i] = elements[i + 1];
            elements[i].index = i;
        }
        elements[newSize] = null;
        size = newSize;
        return valueOf(removed);
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
    }

    private int checkedHash(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private Entry find(Object item, int hash) {
        int bucket = hash & (buckets.length - 1);
        for (Entry entry = buckets[bucket];
                entry != null; entry = entry.next) {
            if (entry.hash == hash && item.equals(entry.item)) {
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
        Entry previous = null;
        Entry current = buckets[bucket];

        while (current != entry) {
            previous = current;
            current = current.next;
        }

        if (previous == null) {
            buckets[bucket] = entry.next;
        } else {
            previous.next = entry.next;
        }
        entry.next = null;
    }

    private void ensureInsertionCapacity() {
        if (size == 2147483647) {
            throw new OutOfMemoryError("Maximum list size reached");
        }

        int required = size + 1;
        Entry[] expandedElements = null;
        Entry[] expandedBuckets = null;

        if (required > elements.length) {
            long proposed = (long) elements.length
                    + (elements.length >>> 1) + 1;
            int capacity = proposed > 2147483647L
                    ? 2147483647 : (int) proposed;
            if (capacity < required) {
                capacity = required;
            }
            expandedElements = new Entry[capacity];
        }

        int threshold = buckets.length - (buckets.length >>> 2);
        if (required > threshold
                && buckets.length < MAX_BUCKET_CAPACITY) {
            expandedBuckets = new Entry[buckets.length << 1];
        }

        // Complete allocations before changing any existing storage.
        if (expandedElements != null) {
            for (int i = 0; i < size; i++) {
                expandedElements[i] = elements[i];
            }
            elements = expandedElements;
        }

        if (expandedBuckets != null) {
            for (int i = 0; i < size; i++) {
                Entry entry = elements[i];
                int bucket = entry.hash & (expandedBuckets.length - 1);
                entry.next = expandedBuckets[bucket];
                expandedBuckets[bucket] = entry;
            }
            buckets = expandedBuckets;
        }
    }

    @SuppressWarnings("unchecked")
    private T valueOf(Entry entry) {
        return (T) entry.item;
    }
}
