@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 8;

    private final Object lock = new Object();
    private Object[] elements = new Object[INITIAL_CAPACITY];
    private Entry[] buckets = new Entry[INITIAL_CAPACITY];
    private int size;

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

    public boolean add(T item) {
        return insert(size(), item);
    }

    public boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            checkInsertIndex(index);

            int hash = spread(item.hashCode());
            if (find(item, hash) != null) {
                return false;
            }

            ensureElementCapacity(size + 1);
            ensureBucketCapacity(size + 1);

            Entry added = new Entry(item, hash, index);
            for (int i = size; i > index; i--) {
                Object shifted = elements[i - 1];
                elements[i] = shifted;
                Entry entry = find(shifted, spread(shifted.hashCode()));
                entry.index = i;
            }

            elements[index] = item;
            size++;
            link(added);
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
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            checkElementIndex(index);

            Object previous = elements[index];
            int newHash = spread(item.hashCode());
            Entry existing = find(item, newHash);

            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("item already exists at another index");
            }

            Entry current = find(previous, spread(previous.hashCode()));
            if (existing != null) {
                if (current.hash != newHash) {
                    unlink(current);
                    current.hash = newHash;
                    link(current);
                }
                current.item = item;
            } else {
                unlink(current);
                current.item = item;
                current.hash = newHash;
                link(current);
            }

            elements[index] = item;
            return cast(previous);
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return removeAtLocked(index, find(elements[index],
                    spread(elements[index].hashCode())));
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            Entry entry = find(item, spread(item.hashCode()));
            if (entry == null) {
                return false;
            }
            removeAtLocked(entry.index, entry);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            return find(item, spread(item.hashCode())) != null;
        }
    }

    public int indexOf(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            Entry entry = find(item, spread(item.hashCode()));
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
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = elements[i];
            }
            return copy;
        }
    }

    private T removeAtLocked(int index, Entry target) {
        Object removed = elements[index];
        unlink(target);

        for (int i = index; i < size - 1; i++) {
            Object shifted = elements[i + 1];
            elements[i] = shifted;
            Entry entry = find(shifted, spread(shifted.hashCode()));
            entry.index = i;
        }

        elements[size - 1] = null;
        size--;
        return cast(removed);
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

    private void ensureElementCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int newCapacity = elements.length << 1;
        if (newCapacity < required || newCapacity <= 0) {
            newCapacity = required;
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private void ensureBucketCapacity(int required) {
        if (required <= buckets.length - buckets.length / 4) {
            return;
        }
        if (buckets.length >= (1 << 30)) {
            throw new OutOfMemoryError("Hash table is too large");
        }

        Entry[] expanded = new Entry[buckets.length << 1];
        for (int i = 0; i < buckets.length; i++) {
            Entry entry = buckets[i];
            while (entry != null) {
                Entry next = entry.next;
                int bucket = entry.hash & (expanded.length - 1);
                entry.next = expanded[bucket];
                expanded[bucket] = entry;
                entry = next;
            }
        }
        buckets = expanded;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private void checkInsertIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    @SuppressWarnings("unchecked")
    private T elementAt(int index) {
        return (T) elements[index];
    }

    @SuppressWarnings("unchecked")
    private T cast(Object value) {
        return (T) value;
    }
}
