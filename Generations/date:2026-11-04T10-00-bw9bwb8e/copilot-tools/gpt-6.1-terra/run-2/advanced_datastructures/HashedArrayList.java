@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 8;
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
    private Entry[] elements = new Entry[INITIAL_CAPACITY];
    private Entry[] buckets = new Entry[INITIAL_CAPACITY];
    private int size;

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
            return itemOf(elements[index]);
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkElementIndex(index);

            int hash = hashOf(item);
            Entry existing = find(item, hash);
            Entry current = elements[index];
            T previous = itemOf(current);

            if (existing != null) {
                if (existing != current) {
                    throw new IllegalArgumentException("Item already exists");
                }
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
            T previous = itemOf(entry);
            removeEntry(entry);
            return previous;
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireItem(item);
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
            requireItem(item);
            return find(item, hashOf(item)) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (lock) {
            requireItem(item);
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

    private boolean insertInternal(int index, T item) {
        requireItem(item);
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException();
        }

        int hash = hashOf(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List is too large");
        }

        Entry entry = new Entry(item, hash, index);
        ensureElementCapacity();
        ensureBucketCapacity();

        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
            elements[i].index = i;
        }
        elements[index] = entry;
        link(entry);
        size++;
        return true;
    }

    private void removeEntry(Entry entry) {
        unlink(entry);
        int index = entry.index;
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            elements[i].index = i;
        }
        elements[size - 1] = null;
        size--;
    }

    private Entry find(Object item, int hash) {
        Entry entry = buckets[hash & (buckets.length - 1)];
        while (entry != null) {
            if (entry.hash == hash && item.equals(entry.item)) {
                return entry;
            }
            entry = entry.next;
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
        while (current != null && current != target) {
            previous = current;
            current = current.next;
        }
        if (current == null) {
            throw new IllegalStateException("Missing hash entry");
        }
        if (previous == null) {
            buckets[bucket] = current.next;
        } else {
            previous.next = current.next;
        }
        current.next = null;
    }

    private void ensureElementCapacity() {
        if (size < elements.length) {
            return;
        }
        int newCapacity = elements.length + (elements.length >> 1) + 1;
        if (newCapacity <= elements.length) {
            newCapacity = Integer.MAX_VALUE;
        }
        Entry[] expanded = new Entry[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private void ensureBucketCapacity() {
        if (buckets.length == MAX_BUCKET_CAPACITY
                || size + 1 <= buckets.length - (buckets.length >> 2)) {
            return;
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
            throw new IndexOutOfBoundsException();
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int hashOf(Object item) {
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    @SuppressWarnings("unchecked")
    private T itemOf(Entry entry) {
        return (T) entry.item;
    }
}
