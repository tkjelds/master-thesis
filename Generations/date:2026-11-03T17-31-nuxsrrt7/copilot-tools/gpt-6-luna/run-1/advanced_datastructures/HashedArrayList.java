@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final byte EMPTY = 0;
    private static final byte ACTIVE = 1;
    private static final byte DELETED = 2;

    private final Object lock = new Object();

    private Object[] elements = new Object[8];
    private int size;

    private Object[] keys = new Object[16];
    private int[] hashes = new int[16];
    private int[] positions = new int[16];
    private byte[] states = new byte[16];
    private int tableSize;
    private int usedSlots;

    public HashedArrayList() {
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return insertLocked(size, item, item.hashCode());
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkPositionIndex(index);
            return insertLocked(index, item, item.hashCode());
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return (T) elements[index];
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkElementIndex(index);

            int hash = item.hashCode();
            int existingSlot = findKey(item, hash);
            if (existingSlot >= 0 && positions[existingSlot] != index) {
                throw new IllegalArgumentException("An equal item already exists");
            }

            T previous = (T) elements[index];
            if (existingSlot >= 0) {
                elements[index] = item;
                keys[existingSlot] = item;
                hashes[existingSlot] = hash;
                return previous;
            }

            int newSlot = findInsertionSlot(hash);
            if (newSlot < 0) {
                throw new IllegalStateException("Hash table has no available slot");
            }

            int oldSlot = findKey(previous, previous.hashCode());
            if (oldSlot < 0) {
                throw new IllegalStateException("Hash index is inconsistent");
            }

            states[oldSlot] = DELETED;
            tableSize--;
            if (states[newSlot] == EMPTY) {
                usedSlots++;
            }
            keys[newSlot] = item;
            hashes[newSlot] = hash;
            positions[newSlot] = index;
            states[newSlot] = ACTIVE;
            tableSize++;
            elements[index] = item;
            return previous;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            Object item = elements[index];
            int slot = findKey(item, item.hashCode());
            if (slot < 0) {
                throw new IllegalStateException("Hash index is inconsistent");
            }
            return (T) removeAtLocked(index, slot);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int slot = findKey(item, item.hashCode());
            if (slot < 0) {
                return false;
            }
            removeAtLocked(positions[slot], slot);
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return findKey(item, item.hashCode()) >= 0;
        }
    }

    public int indexOf(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int slot = findKey(item, item.hashCode());
            return slot < 0 ? -1 : positions[slot];
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

    private boolean insertLocked(int index, T item, int hash) {
        if (findKey(item, hash) >= 0) {
            return false;
        }

        ensureArrayCapacity(size + 1);
        ensureHashCapacityForOne();
        int slot = findInsertionSlot(hash);
        if (slot < 0) {
            throw new IllegalStateException("Hash table has no available slot");
        }

        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
        }
        for (int i = 0; i < states.length; i++) {
            if (states[i] == ACTIVE && positions[i] >= index) {
                positions[i]++;
            }
        }

        if (states[slot] == EMPTY) {
            usedSlots++;
        }
        keys[slot] = item;
        hashes[slot] = hash;
        positions[slot] = index;
        states[slot] = ACTIVE;
        tableSize++;
        elements[index] = item;
        size++;
        return true;
    }

    private Object removeAtLocked(int index, int slot) {
        Object removed = elements[index];
        states[slot] = DELETED;
        tableSize--;

        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
        }
        elements[size - 1] = null;

        for (int i = 0; i < states.length; i++) {
            if (states[i] == ACTIVE && positions[i] > index) {
                positions[i]--;
            }
        }
        size--;
        return removed;
    }

    private int findKey(Object key, int hash) {
        int mask = keys.length - 1;
        int slot = spread(hash) & mask;
        for (int probes = 0; probes < keys.length; probes++) {
            if (states[slot] == EMPTY) {
                return -1;
            }
            if (states[slot] == ACTIVE
                    && hashes[slot] == hash
                    && keys[slot].equals(key)) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private int findInsertionSlot(int hash) {
        int mask = keys.length - 1;
        int slot = spread(hash) & mask;
        int firstDeleted = -1;
        for (int probes = 0; probes < keys.length; probes++) {
            if (states[slot] == DELETED) {
                if (firstDeleted < 0) {
                    firstDeleted = slot;
                }
            } else if (states[slot] == EMPTY) {
                return firstDeleted >= 0 ? firstDeleted : slot;
            }
            slot = (slot + 1) & mask;
        }
        return firstDeleted;
    }

    private void ensureArrayCapacity(int minimumCapacity) {
        if (minimumCapacity <= elements.length) {
            return;
        }

        long candidate = (long) elements.length + (elements.length >> 1) + 1;
        if (candidate < minimumCapacity) {
            candidate = minimumCapacity;
        }
        if (candidate > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Required array size too large");
        }

        Object[] expanded = new Object[(int) candidate];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private void ensureHashCapacityForOne() {
        int capacity = keys.length;
        int threshold = capacity - capacity / 3;
        if (usedSlots + 1 <= threshold) {
            return;
        }

        if (tableSize + 1 <= threshold) {
            rehash(capacity);
            return;
        }

        if (capacity >= (1 << 30)) {
            throw new OutOfMemoryError("Required hash table size too large");
        }
        rehash(capacity << 1);
    }

    private void rehash(int capacity) {
        Object[] newKeys = new Object[capacity];
        int[] newHashes = new int[capacity];
        int[] newPositions = new int[capacity];
        byte[] newStates = new byte[capacity];
        int mask = capacity - 1;

        for (int i = 0; i < states.length; i++) {
            if (states[i] == ACTIVE) {
                int slot = spread(hashes[i]) & mask;
                while (newStates[slot] == ACTIVE) {
                    slot = (slot + 1) & mask;
                }
                newKeys[slot] = keys[i];
                newHashes[slot] = hashes[i];
                newPositions[slot] = positions[i];
                newStates[slot] = ACTIVE;
            }
        }

        keys = newKeys;
        hashes = newHashes;
        positions = newPositions;
        states = newStates;
        usedSlots = tableSize;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Size: " + size);
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }
}
