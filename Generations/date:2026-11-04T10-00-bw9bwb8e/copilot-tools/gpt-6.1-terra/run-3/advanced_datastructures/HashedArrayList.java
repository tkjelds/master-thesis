@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
    private static final int MAX_TABLE_SIZE = 1 << 30;

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
    private Entry[] elements = new Entry[8];
    private Entry[] buckets = new Entry[16];
    private int count;

    // Operations do not wait for elements or capacity; invalid indices throw,
    // and allocation failure is reported by the JVM.
    public boolean add(T item) {
        synchronized (lock) {
            return insertInternal(count, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkPositionIndex(index);
            return insertInternal(index, item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return itemOf(elements[index]);
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkElementIndex(index);

            int hash = hash(item);
            Entry existing = find(item, hash);
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("Item already exists");
            }

            Entry entry = elements[index];
            T previous = itemOf(entry);
            if (existing == entry) {
                entry.item = item;
            } else {
                unlink(entry);
                entry.item = item;
                entry.hash = hash;
                int bucket = bucketIndex(hash, buckets.length);
                entry.next = buckets[bucket];
                buckets[bucket] = entry;
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
        synchronized (lock) {
            requireItem(item);
            Entry entry = find(item, hash(item));
            if (entry == null) {
                return false;
            }
            removeAtInternal(entry.index);
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
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[count];
            for (int i = 0; i < count; i++) {
                result[i] = elements[i].item;
            }
            return result;
        }
    }

    private boolean insertInternal(int index, T item) {
        requireItem(item);
        int hash = hash(item);
        if (find(item, hash) != null) {
            return false;
        }

        Entry entry = new Entry(item, hash, index);
        ensureCapacityForInsertion();

        for (int i = count; i > index; i--) {
            Entry shifted = elements[i - 1];
            elements[i] = shifted;
            shifted.index = i;
        }
        elements[index] = entry;
        int bucket = bucketIndex(hash, buckets.length);
        entry.next = buckets[bucket];
        buckets[bucket] = entry;
        count++;
        return true;
    }

    private T removeAtInternal(int index) {
        Entry entry = elements[index];
        T previous = itemOf(entry);
        unlink(entry);

        for (int i = index; i < count - 1; i++) {
            Entry shifted = elements[i + 1];
            elements[i] = shifted;
            shifted.index = i;
        }
        elements[--count] = null;
        return previous;
    }

    private void ensureCapacityForInsertion() {
        Entry[] newElements = elements;
        Entry[] newBuckets = buckets;

        if (count == elements.length) {
            if (elements.length >= MAX_ARRAY_SIZE) {
                throw new OutOfMemoryError("List capacity exceeded");
            }
            int newCapacity = elements.length <= MAX_ARRAY_SIZE / 2
                    ? elements.length * 2 : MAX_ARRAY_SIZE;
            newElements = new Entry[newCapacity];
            for (int i = 0; i < count; i++) {
                newElements[i] = elements[i];
            }
        }

        if (count + 1 > buckets.length - buckets.length / 4
                && buckets.length < MAX_TABLE_SIZE) {
            newBuckets = new Entry[buckets.length * 2];
        }

        if (newBuckets != buckets) {
            for (int i = 0; i < buckets.length; i++) {
                Entry entry = buckets[i];
                while (entry != null) {
                    Entry next = entry.next;
                    int bucket = bucketIndex(entry.hash, newBuckets.length);
                    entry.next = newBuckets[bucket];
                    newBuckets[bucket] = entry;
                    entry = next;
                }
            }
            buckets = newBuckets;
        }
        elements = newElements;
    }

    private Entry find(Object item, int hash) {
        Entry entry = buckets[bucketIndex(hash, buckets.length)];
        while (entry != null) {
            if (entry.hash == hash && item.equals(entry.item)) {
                return entry;
            }
            entry = entry.next;
        }
        return null;
    }

    private void unlink(Entry target) {
        int bucket = bucketIndex(target.hash, buckets.length);
        Entry entry = buckets[bucket];
        Entry previous = null;
        while (entry != target) {
            previous = entry;
            entry = entry.next;
        }
        if (previous == null) {
            buckets[bucket] = entry.next;
        } else {
            previous.next = entry.next;
        }
        entry.next = null;
    }

    private static int hash(Object item) {
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int bucketIndex(int hash, int length) {
        return hash & (length - 1);
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + count);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + count);
        }
    }

    @SuppressWarnings("unchecked")
    private T itemOf(Entry entry) {
        return (T) entry.item;
    }
}
