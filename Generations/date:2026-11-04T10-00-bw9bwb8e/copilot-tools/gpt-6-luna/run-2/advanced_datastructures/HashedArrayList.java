@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private Object[] elements = new Object[8];
    private int size;

    private Object[] keys = new Object[16];
    private int[] hashes = new int[16];
    private int[] indices = new int[16];
    private byte[] states = new byte[16];
    private int entryCount;
    private int used;

    public HashedArrayList() {
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            int hash = item.hashCode();
            if (findSlot(item, hash) >= 0) {
                return false;
            }
            ensureElementCapacity(size + 1);
            prepareForInsert();
            insertAt(size, item, hash);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            checkPositionIndex(index);
            int hash = item.hashCode();
            if (findSlot(item, hash) >= 0) {
                return false;
            }
            ensureElementCapacity(size + 1);
            prepareForInsert();
            insertAt(index, item, hash);
            return true;
        }
    }

    public T get(int index) {
        synchronized (this) {
            checkElementIndex(index);
            return elementAt(index);
        }
    }

    public T set(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            checkElementIndex(index);
            int hash = item.hashCode();
            int matchingSlot = findSlot(item, hash);
            if (matchingSlot >= 0 && indices[matchingSlot] != index) {
                throw new IllegalArgumentException();
            }

            T previous = elementAt(index);
            if (matchingSlot >= 0) {
                elements[index] = item;
                keys[matchingSlot] = item;
                hashes[matchingSlot] = hash;
                return previous;
            }

            prepareForInsert();
            int oldSlot = findIdentitySlot(previous);
            if (oldSlot < 0) {
                throw new IllegalStateException();
            }
            deleteEntryAtSlot(oldSlot);
            elements[index] = item;
            insertEntry(item, hash, index);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (this) {
            checkElementIndex(index);
            return removeAtIndex(index);
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            int slot = findSlot(item, item.hashCode());
            if (slot < 0) {
                return false;
            }
            removeAtIndex(indices[slot]);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            return findSlot(item, item.hashCode()) >= 0;
        }
    }

    public int indexOf(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            int slot = findSlot(item, item.hashCode());
            return slot < 0 ? -1 : indices[slot];
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            Object[] result = new Object[size];
            for (int i = 0; i < size; i++) {
                result[i] = elements[i];
            }
            return result;
        }
    }

    private void insertAt(int index, Object item, int hash) {
        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
        }
        for (int i = 0; i < states.length; i++) {
            if (states[i] == 1 && indices[i] >= index) {
                indices[i]++;
            }
        }
        elements[index] = item;
        insertEntry(item, hash, index);
        size++;
    }

    private T removeAtIndex(int index) {
        Object removed = elements[index];
        int slot = findIdentitySlot(removed);
        if (slot < 0) {
            throw new IllegalStateException();
        }
        deleteEntryAtSlot(slot);
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
        }
        elements[size - 1] = null;
        for (int i = 0; i < states.length; i++) {
            if (states[i] == 1 && indices[i] > index) {
                indices[i]--;
            }
        }
        size--;
        return cast(removed);
    }

    private int findSlot(Object item, int hash) {
        int mask = keys.length - 1;
        int position = spread(hash) & mask;
        for (int probes = 0; probes < keys.length; probes++) {
            if (states[position] == 0) {
                return -1;
            }
            if (states[position] == 1
                    && hashes[position] == hash
                    && keys[position].equals(item)) {
                return position;
            }
            position = (position + 1) & mask;
        }
        return -1;
    }

    private int findIdentitySlot(Object item) {
        for (int i = 0; i < states.length; i++) {
            if (states[i] == 1 && keys[i] == item) {
                return i;
            }
        }
        return -1;
    }

    private void insertEntry(Object item, int hash, int index) {
        int mask = keys.length - 1;
        int position = spread(hash) & mask;
        int firstTombstone = -1;
        while (states[position] != 0) {
            if (states[position] == 2 && firstTombstone < 0) {
                firstTombstone = position;
            }
            position = (position + 1) & mask;
        }
        if (firstTombstone >= 0) {
            position = firstTombstone;
        } else {
            used++;
        }
        keys[position] = item;
        hashes[position] = hash;
        indices[position] = index;
        states[position] = 1;
        entryCount++;
    }

    private void deleteEntryAtSlot(int slot) {
        keys[slot] = null;
        states[slot] = 2;
        entryCount--;
    }

    private void prepareForInsert() {
        int threshold = keys.length - (keys.length >>> 2);
        if (entryCount + 1 > threshold) {
            if (keys.length > Integer.MAX_VALUE / 2) {
                throw new OutOfMemoryError();
            }
            rehash(keys.length * 2);
        } else if (used + 1 > threshold) {
            rehash(keys.length);
        }
    }

    private void rehash(int capacity) {
        Object[] newKeys = new Object[capacity];
        int[] newHashes = new int[capacity];
        int[] newIndices = new int[capacity];
        byte[] newStates = new byte[capacity];

        for (int i = 0; i < states.length; i++) {
            if (states[i] == 1) {
                placeEntry(newKeys, newHashes, newIndices, newStates,
                        keys[i], hashes[i], indices[i]);
            }
        }

        keys = newKeys;
        hashes = newHashes;
        indices = newIndices;
        states = newStates;
        used = entryCount;
    }

    private static void placeEntry(Object[] keys, int[] hashes, int[] indices,
                                   byte[] states, Object key, int hash, int index) {
        int mask = keys.length - 1;
        int position = spread(hash) & mask;
        while (states[position] != 0) {
            position = (position + 1) & mask;
        }
        keys[position] = key;
        hashes[position] = hash;
        indices[position] = index;
        states[position] = 1;
    }

    private void ensureElementCapacity(int required) {
        if (required <= elements.length) {
            return;
        }
        if (elements.length > Integer.MAX_VALUE / 2) {
            throw new OutOfMemoryError();
        }
        Object[] newElements = new Object[elements.length * 2];
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[i];
        }
        elements = newElements;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException();
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
    private T cast(Object item) {
        return (T) item;
    }
}
