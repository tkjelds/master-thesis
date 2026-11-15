@HashedArrayList.ThreadSafe
public final class HashedArrayList<T> {
    @interface ThreadSafe {}


    private static final int INITIAL_CAPACITY = 8;
    private static final int INITIAL_BUCKETS = 16;

    // Blocking policy: this list is unbounded, so no operation waits for capacity.
    // Every operation either completes immediately under the monitor or fails fast:
    // duplicates return false, invalid indices throw IndexOutOfBoundsException,
    // conflicting set() throws IllegalArgumentException, and null throws NullPointerException.
    // Because nothing blocks, no wait/notify is needed; all state is guarded by one monitor.

    private final Object lock = new Object();

    // Sequence storage: elements[0..size) in order.
    private Object[] elements;
    // Cached (spread) hash code for the item at each position.
    private int[] hashes;
    // Hash-table chain links: chainNext[p] is the next position in the same bucket, or -1.
    private int[] chainNext;
    // Hash-table bucket heads: buckets[b] is the first position in bucket b, or -1.
    private int[] buckets;
    private int size;

    public HashedArrayList() {
        elements = new Object[INITIAL_CAPACITY];
        hashes = new int[INITIAL_CAPACITY];
        chainNext = new int[INITIAL_CAPACITY];
        buckets = new int[INITIAL_BUCKETS];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = -1;
        }
        size = 0;
    }

    // ---------- Public operations ----------

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int h = hashOf(item);
            if (findIndex(item, h) != -1) {
                return false;
            }
            appendLocked(item, h);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index " + index + " out of range [0, " + size + "]");
            }
            int h = hashOf(item);
            if (findIndex(item, h) != -1) {
                return false;
            }
            if (index == size) {
                appendLocked(item, h);
            } else {
                insertAtLocked(index, item, h);
            }
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return (T) elements[index];
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkIndex(index);
            int h = hashOf(item);
            int existing = findIndex(item, h);
            if (existing != -1 && existing != index) {
                throw new IllegalArgumentException("an equal item already exists at index " + existing);
            }
            Object old = elements[index];
            elements[index] = item;
            hashes[index] = h;
            if (existing == -1) {
                // The hash/equality of the replaced item may differ; relink the index.
                rebuildIndex();
            }
            // If existing == index, the new item is equal to the old one and has the same hash,
            // so the index entry remains valid.
            return (T) old;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (lock) {
            checkIndex(index);
            return (T) removeAtLocked(index);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int idx = findIndex(item, hashOf(item));
            if (idx == -1) {
                return false;
            }
            removeAtLocked(idx);
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return findIndex(item, hashOf(item)) != -1;
        }
    }

    public int indexOf(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return findIndex(item, hashOf(item));
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

    // ---------- Internal helpers (caller must hold lock) ----------

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("null items are not permitted");
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + " out of range [0, " + size + ")");
        }
    }

    private static int hashOf(Object item) {
        int h = item.hashCode();
        return h ^ (h >>> 16);
    }

    private int findIndex(Object item, int h) {
        int p = buckets[h & (buckets.length - 1)];
        while (p != -1) {
            if (hashes[p] == h && item.equals(elements[p])) {
                return p;
            }
            p = chainNext[p];
        }
        return -1;
    }

    private void ensureElementCapacity(int minCapacity) {
        if (minCapacity <= elements.length) {
            return;
        }
        int newCap = elements.length * 2;
        while (newCap < minCapacity) {
            newCap *= 2;
        }
        Object[] newElements = new Object[newCap];
        int[] newHashes = new int[newCap];
        int[] newChain = new int[newCap];
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[i];
            newHashes[i] = hashes[i];
            newChain[i] = chainNext[i];
        }
        elements = newElements;
        hashes = newHashes;
        chainNext = newChain;
    }

    private void linkIndex(int p) {
        int b = hashes[p] & (buckets.length - 1);
        chainNext[p] = buckets[b];
        buckets[b] = p;
    }

    // Rebuilds all hash-table entries from the sequence, growing buckets if the load is too high.
    private void rebuildIndex() {
        while ((long) size * 4 > (long) buckets.length * 3) {
            buckets = new int[buckets.length * 2];
        }
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = -1;
        }
        for (int p = 0; p < size; p++) {
            linkIndex(p);
        }
    }

    private void appendLocked(Object item, int h) {
        ensureElementCapacity(size + 1);
        elements[size] = item;
        hashes[size] = h;
        size++;
        if ((long) size * 4 > (long) buckets.length * 3) {
            rebuildIndex();
        } else {
            linkIndex(size - 1);
        }
    }

    private void insertAtLocked(int index, Object item, int h) {
        ensureElementCapacity(size + 1);
        System.arraycopy(elements, index, elements, index + 1, size - index);
        System.arraycopy(hashes, index, hashes, index + 1, size - index);
        elements[index] = item;
        hashes[index] = h;
        size++;
        rebuildIndex();
    }

    private Object removeAtLocked(int index) {
        Object old = elements[index];
        int tail = size - index - 1;
        System.arraycopy(elements, index + 1, elements, index, tail);
        System.arraycopy(hashes, index + 1, hashes, index, tail);
        size--;
        elements[size] = null;
        rebuildIndex();
        return old;
    }
}
