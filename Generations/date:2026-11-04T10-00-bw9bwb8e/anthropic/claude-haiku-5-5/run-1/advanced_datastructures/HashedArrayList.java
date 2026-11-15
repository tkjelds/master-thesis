@HashedArrayList.ThreadSafe
public final class HashedArrayList<T> {
    @interface ThreadSafe {}


    private static final int INITIAL_CAPACITY = 16;
    private static final int INITIAL_BUCKETS = 16;

    // Single collection-wide monitor guarding all state.
    private final Object lock = new Object();

    // Sequence storage: elements[0..size) in order.
    private Object[] elements;
    // Cached (stable) hash per sequence position.
    private int[] hashes;
    // Hash chain links per sequence position: next position + 1, or 0 for end of chain.
    private int[] next;
    // Bucket heads: position + 1 of first entry in the chain, or 0 if empty.
    private int[] buckets;
    private int size;

    // Blocking policy: the list is unbounded, so no operation waits for a condition.
    // Operations that cannot succeed (invalid index, duplicate, absent item) either
    // return false immediately or throw an exception; nothing blocks or busy-waits.

    public HashedArrayList() {
        elements = new Object[INITIAL_CAPACITY];
        hashes = new int[INITIAL_CAPACITY];
        next = new int[INITIAL_CAPACITY];
        buckets = new int[INITIAL_BUCKETS];
        size = 0;
    }

    public boolean add(T item) {
        requireNonNull(item);
        int h = hashOf(item);
        synchronized (lock) {
            if (findLocked(item, h) >= 0) {
                return false;
            }
            ensureElementCapacity();
            int p = size;
            elements[p] = item;
            hashes[p] = h;
            size++;
            if (!growBucketsIfNeeded()) {
                linkHead(p);
            }
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        int h = hashOf(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
            }
            if (findLocked(item, h) >= 0) {
                return false;
            }
            ensureElementCapacity();
            for (int i = size; i > index; i--) {
                elements[i] = elements[i - 1];
                hashes[i] = hashes[i - 1];
            }
            elements[index] = item;
            hashes[index] = h;
            size++;
            if (!growBucketsIfNeeded()) {
                relinkAllLocked();
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
        int h = hashOf(item);
        synchronized (lock) {
            checkIndex(index);
            Object old = elements[index];
            if (hashes[index] == h && item.equals(old)) {
                // Equal to the item already at this index: replace representative in place.
                elements[index] = item;
                return (T) old;
            }
            int p = findLocked(item, h);
            if (p >= 0 && p != index) {
                throw new IllegalArgumentException("equal item already present at index " + p);
            }
            unlinkLocked(index);
            elements[index] = item;
            hashes[index] = h;
            linkHead(index);
            return (T) old;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkIndex(index);
            return removeAtLocked(index);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        int h = hashOf(item);
        synchronized (lock) {
            int p = findLocked(item, h);
            if (p < 0) {
                return false;
            }
            removeAtLocked(p);
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        int h = hashOf(item);
        synchronized (lock) {
            return findLocked(item, h) >= 0;
        }
    }

    public int indexOf(T item) {
        requireNonNull(item);
        int h = hashOf(item);
        synchronized (lock) {
            return findLocked(item, h);
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[size];
            for (int i = 0; i < size; i++) {
                out[i] = elements[i];
            }
            return out;
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers. All methods suffixed with Locked or called from
    // within synchronized (lock) blocks must only be invoked while holding lock.
    // ---------------------------------------------------------------------

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("null items are not permitted");
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private static int hashOf(Object item) {
        int h = item.hashCode();
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    private int bucketOf(int h) {
        return h & (buckets.length - 1);
    }

    // Returns the sequence position of an item equal to 'item', or -1.
    private int findLocked(Object item, int h) {
        int p = buckets[bucketOf(h)] - 1;
        while (p >= 0) {
            if (hashes[p] == h && item.equals(elements[p])) {
                return p;
            }
            p = next[p] - 1;
        }
        return -1;
    }

    private void ensureElementCapacity() {
        if (size < elements.length) {
            return;
        }
        int newCap = elements.length << 1;
        Object[] newElements = new Object[newCap];
        int[] newHashes = new int[newCap];
        int[] newNext = new int[newCap];
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[i];
            newHashes[i] = hashes[i];
            newNext[i] = next[i];
        }
        elements = newElements;
        hashes = newHashes;
        next = newNext;
    }

    // Grows the bucket table when load factor exceeds 3/4. Returns true if a
    // full relink was performed (so the caller need not link the new entry itself).
    private boolean growBucketsIfNeeded() {
        if (size > buckets.length - (buckets.length >>> 2)) {
            buckets = new int[buckets.length << 1];
            relinkAllLocked();
            return true;
        }
        return false;
    }

    // Rebuilds all hash chains from the current sequence. O(n + buckets).
    private void relinkAllLocked() {
        for (int b = 0; b < buckets.length; b++) {
            buckets[b] = 0;
        }
        for (int p = 0; p < size; p++) {
            int b = bucketOf(hashes[p]);
            next[p] = buckets[b];
            buckets[b] = p + 1;
        }
    }

    private void linkHead(int p) {
        int b = bucketOf(hashes[p]);
        next[p] = buckets[b];
        buckets[b] = p + 1;
    }

    // Removes position p from its hash chain. Uses the hash currently stored at p.
    private void unlinkLocked(int p) {
        int b = bucketOf(hashes[p]);
        int head = buckets[b] - 1;
        if (head == p) {
            buckets[b] = next[p];
            return;
        }
        int prev = head;
        while (prev >= 0) {
            int nx = next[prev] - 1;
            if (nx == p) {
                next[prev] = next[p];
                return;
            }
            prev = nx;
        }
    }

    // Removes the element at a validated index, shifting the sequence and rebuilding the index.
    private T removeAtLocked(int index) {
        @SuppressWarnings("unchecked")
        T old = (T) elements[index];
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            hashes[i] = hashes[i + 1];
        }
        size--;
        elements[size] = null;
        hashes[size] = 0;
        next[size] = 0;
        relinkAllLocked();
        return old;
    }
}
