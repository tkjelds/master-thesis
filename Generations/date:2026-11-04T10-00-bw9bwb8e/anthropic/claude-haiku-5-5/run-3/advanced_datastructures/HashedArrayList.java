@HashedArrayList.ThreadSafe
public final class HashedArrayList<T> {
    @interface ThreadSafe {}


    private static final int INITIAL_CAPACITY = 16;

    private final Object lock = new Object();

    // Sequence storage: elems[0..size) is the ordered contents.
    private Object[] elems;
    // Spread hash code of the item at each position, cached for fast comparisons.
    private int[] hashes;
    // Hash chain link: next[p] is the next position in the same bucket, or -1.
    private int[] next;
    // Bucket heads: buckets[b] is the first position in bucket b, or -1.
    // buckets.length == elems.length, always a power of two, so load factor <= 1.
    private int[] buckets;
    private int size;

    public HashedArrayList() {
        elems = new Object[INITIAL_CAPACITY];
        hashes = new int[INITIAL_CAPACITY];
        next = new int[INITIAL_CAPACITY];
        buckets = new int[INITIAL_CAPACITY];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = -1;
        }
        size = 0;
    }

    /*
     * Policy: the list is unbounded, so no operation blocks for capacity.
     * Operations never wait; invalid requests throw immediately and leave
     * the list unchanged. All state changes happen under a single monitor,
     * so every operation is atomic and linearizable.
     */

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int h = spread(item.hashCode());
            if (findLocked(item, h) != -1) {
                return false;
            }
            ensureCapacityLocked(size + 1);
            elems[size] = item;
            hashes[size] = h;
            size++;
            rebuildIndexLocked();
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            int h = spread(item.hashCode());
            if (findLocked(item, h) != -1) {
                return false;
            }
            ensureCapacityLocked(size + 1);
            for (int i = size; i > index; i--) {
                elems[i] = elems[i - 1];
                hashes[i] = hashes[i - 1];
            }
            elems[index] = item;
            hashes[index] = h;
            size++;
            rebuildIndexLocked();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            checkIndexLocked(index);
            return (T) elems[index];
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkIndexLocked(index);
            int h = spread(item.hashCode());
            int p = findLocked(item, h);
            if (p != -1 && p != index) {
                throw new IllegalArgumentException("item already present at index " + p);
            }
            T old = (T) elems[index];
            elems[index] = item;
            if (p == index) {
                // Equal item: hash is identical (equal items have equal hashes),
                // so the bucket chain remains valid.
                hashes[index] = h;
            } else {
                hashes[index] = h;
                rebuildIndexLocked();
            }
            return old;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (lock) {
            checkIndexLocked(index);
            T removed = (T) elems[index];
            for (int i = index; i < size - 1; i++) {
                elems[i] = elems[i + 1];
                hashes[i] = hashes[i + 1];
            }
            size--;
            elems[size] = null;
            hashes[size] = 0;
            rebuildIndexLocked();
            return removed;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int p = findLocked(item, spread(item.hashCode()));
            if (p == -1) {
                return false;
            }
            removeAtLocked(p);
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return findLocked(item, spread(item.hashCode())) != -1;
        }
    }

    public int indexOf(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return findLocked(item, spread(item.hashCode()));
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
                copy[i] = elems[i];
            }
            return copy;
        }
    }

    // ----- Internal helpers; all must be called while holding lock. -----

    private static void requireNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null items are not allowed");
        }
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private void checkIndexLocked(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
    }

    private int findLocked(Object item, int h) {
        int mask = buckets.length - 1;
        for (int p = buckets[h & mask]; p != -1; p = next[p]) {
            if (hashes[p] == h && elems[p].equals(item)) {
                return p;
            }
        }
        return -1;
    }

    private void removeAtLocked(int index) {
        for (int i = index; i < size - 1; i++) {
            elems[i] = elems[i + 1];
            hashes[i] = hashes[i + 1];
        }
        size--;
        elems[size] = null;
        hashes[size] = 0;
        rebuildIndexLocked();
    }

    private void ensureCapacityLocked(int minCapacity) {
        if (minCapacity <= elems.length) {
            return;
        }
        int newCap = elems.length;
        while (newCap < minCapacity) {
            newCap <<= 1;
        }
        Object[] newElems = new Object[newCap];
        int[] newHashes = new int[newCap];
        int[] newNext = new int[newCap];
        int[] newBuckets = new int[newCap];
        for (int i = 0; i < size; i++) {
            newElems[i] = elems[i];
            newHashes[i] = hashes[i];
        }
        // Publish all fields only after the new state is fully built.
        elems = newElems;
        hashes = newHashes;
        next = newNext;
        buckets = newBuckets;
        rebuildIndexLocked();
    }

    /** Rebuilds the entire hash index from the sequence array (O(n)). */
    private void rebuildIndexLocked() {
        int mask = buckets.length - 1;
        for (int b = 0; b <= mask; b++) {
            buckets[b] = -1;
        }
        for (int p = 0; p < size; p++) {
            int b = hashes[p] & mask;
            next[p] = buckets[b];
            buckets[b] = p;
        }
    }
}
