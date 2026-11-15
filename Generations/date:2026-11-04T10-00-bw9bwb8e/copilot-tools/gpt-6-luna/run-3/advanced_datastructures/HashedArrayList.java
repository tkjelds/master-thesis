@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[8];
    private HashEntry[] buckets = new HashEntry[16];
    private int size;

    private static final class HashEntry {
        final Object item;
        final int hash;
        int index;
        HashEntry next;

        HashEntry(Object item, int hash, int index, HashEntry next) {
            this.item = item;
            this.hash = hash;
            this.index = index;
            this.next = next;
        }
    }

    public boolean add(T item) {
        synchronized (lock) {
            requireNonNull(item);
            return insertLocked(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            requireNonNull(item);
            checkPositionIndex(index);
            return insertLocked(index, item);
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

            int hash = item.hashCode();
            HashEntry existing = findEntry(item, hash);
            HashEntry oldEntry = findEntryAtIndex(index);
            if (oldEntry == null) {
                throw new IllegalStateException("Missing hash entry for list element");
            }
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("An equal item already exists");
            }

            T previous = elementAt(index);
            if (existing == oldEntry) {
                oldEntry.next = oldEntry.next;
                replaceEntryItem(oldEntry, item, hash);
            } else {
                HashEntry replacement = new HashEntry(item, hash, index, null);
                removeEntry(oldEntry);
                addEntry(replacement);
            }
            elements[index] = item;
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return removeAtLocked(index);
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireNonNull(item);
            HashEntry entry = findEntry(item, item.hashCode());
            if (entry == null) {
                return false;
            }
            removeAtLocked(entry.index);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireNonNull(item);
            return findEntry(item, item.hashCode()) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (lock) {
            requireNonNull(item);
            HashEntry entry = findEntry(item, item.hashCode());
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

    private boolean insertLocked(int index, T item) {
        int hash = item.hashCode();
        if (findEntry(item, hash) != null) {
            return false;
        }

        ensureElementCapacity(size + 1);
        ensureHashCapacity(size + 1);
        HashEntry entry = new HashEntry(item, hash, index, null);

        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
        }
        elements[index] = item;
        updateIndicesForInsertion(index);
        addEntry(entry);
        size++;
        return true;
    }

    private T removeAtLocked(int index) {
        HashEntry entry = findEntryAtIndex(index);
        if (entry == null) {
            throw new IllegalStateException("Missing hash entry for list element");
        }

        T removed = elementAt(index);
        removeEntry(entry);
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
        }
        elements[size - 1] = null;
        size--;
        updateIndicesForRemoval(index);
        return removed;
    }

    private void replaceEntryItem(HashEntry entry, T item, int hash) {
        entry.next = entry.next;
        HashEntry replacement = new HashEntry(item, hash, entry.index, entry.next);
        replaceEntryNode(entry, replacement);
    }

    private void replaceEntryNode(HashEntry oldEntry, HashEntry replacement) {
        int bucket = bucketIndex(oldEntry.hash, buckets.length);
        HashEntry previous = null;
        HashEntry current = buckets[bucket];
        while (current != null) {
            if (current == oldEntry) {
                replacement.next = current.next;
                if (previous == null) {
                    buckets[bucket] = replacement;
                } else {
                    previous.next = replacement;
                }
                return;
            }
            previous = current;
            current = current.next;
        }
        throw new IllegalStateException("Missing hash entry");
    }

    private HashEntry findEntry(Object item, int hash) {
        int bucket = bucketIndex(hash, buckets.length);
        for (HashEntry entry = buckets[bucket]; entry != null; entry = entry.next) {
            if (entry.hash == hash && item.equals(entry.item)) {
                return entry;
            }
        }
        return null;
    }

    private HashEntry findEntryAtIndex(int index) {
        for (HashEntry bucket : buckets) {
            for (HashEntry entry = bucket; entry != null; entry = entry.next) {
                if (entry.index == index) {
                    return entry;
                }
            }
        }
        return null;
    }

    private void addEntry(HashEntry entry) {
        int bucket = bucketIndex(entry.hash, buckets.length);
        entry.next = buckets[bucket];
        buckets[bucket] = entry;
    }

    private void removeEntry(HashEntry target) {
        int bucket = bucketIndex(target.hash, buckets.length);
        HashEntry previous = null;
        HashEntry current = buckets[bucket];
        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    buckets[bucket] = current.next;
                } else {
                    previous.next = current.next;
                }
                return;
            }
            previous = current;
            current = current.next;
        }
        throw new IllegalStateException("Missing hash entry");
    }

    private void updateIndicesForInsertion(int index) {
        for (HashEntry bucket : buckets) {
            for (HashEntry entry = bucket; entry != null; entry = entry.next) {
                if (entry.index >= index) {
                    entry.index++;
                }
            }
        }
    }

    private void updateIndicesForRemoval(int index) {
        for (HashEntry bucket : buckets) {
            for (HashEntry entry = bucket; entry != null; entry = entry.next) {
                if (entry.index > index) {
                    entry.index--;
                }
            }
        }
    }

    private void ensureElementCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int capacity = elements.length;
        while (capacity < required) {
            if (capacity > (Integer.MAX_VALUE - 8) / 2) {
                capacity = Integer.MAX_VALUE - 8;
                break;
            }
            capacity *= 2;
        }
        if (capacity < required) {
            throw new OutOfMemoryError("List capacity exceeded");
        }

        Object[] expanded = new Object[capacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private void ensureHashCapacity(int required) {
        if (required <= (buckets.length * 3) / 4) {
            return;
        }
        if (buckets.length > Integer.MAX_VALUE / 2) {
            throw new OutOfMemoryError("Hash table capacity exceeded");
        }

        HashEntry[] expanded = new HashEntry[buckets.length * 2];
        for (HashEntry bucket : buckets) {
            for (HashEntry entry = bucket; entry != null; entry = entry.next) {
                int newBucket = bucketIndex(entry.hash, expanded.length);
                expanded[newBucket] = new HashEntry(
                    entry.item, entry.hash, entry.index, expanded[newBucket]);
            }
        }
        buckets = expanded;
    }

    private static int bucketIndex(int hash, int capacity) {
        int spread = hash ^ (hash >>> 16);
        return spread & (capacity - 1);
    }

    private static <E> E requireNonNull(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        return item;
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

    @SuppressWarnings("unchecked")
    private T elementAt(int index) {
        return (T) elements[index];
    }
}
