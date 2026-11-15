@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private Object[] elements = new Object[8];
    private int[] elementHashes = new int[8];
    private int[] hashSlots = new int[8];
    private int size;
    private final HashIndex index = new HashIndex();

    public boolean add(T item) {
        synchronized (this) {
            requireNonNull(item);
            int hash = item.hashCode();
            if (index.findSlot(item, hash) >= 0) {
                return false;
            }

            ensureArrayCapacity(size + 1);
            ensureHashCapacityForInsert();

            int slot = index.putAbsent(item, hash, size);
            elements[size] = item;
            elementHashes[size] = hash;
            hashSlots[size] = slot;
            size++;
            return true;
        }
    }

    public boolean insert(int indexToInsert, T item) {
        synchronized (this) {
            requireNonNull(item);
            if (indexToInsert < 0 || indexToInsert > size) {
                throw new IndexOutOfBoundsException(
                    "Index: " + indexToInsert + ", Size: " + size);
            }

            int hash = item.hashCode();
            if (index.findSlot(item, hash) >= 0) {
                return false;
            }

            ensureArrayCapacity(size + 1);
            ensureHashCapacityForInsert();

            int slot = index.putAbsent(item, hash, indexToInsert);
            for (int i = size; i > indexToInsert; i--) {
                elements[i] = elements[i - 1];
                elementHashes[i] = elementHashes[i - 1];
                hashSlots[i] = hashSlots[i - 1];
                index.setValueAtSlot(hashSlots[i], i);
            }

            elements[indexToInsert] = item;
            elementHashes[indexToInsert] = hash;
            hashSlots[indexToInsert] = slot;
            size++;
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int indexToGet) {
        synchronized (this) {
            checkElementIndex(indexToGet);
            return (T) elements[indexToGet];
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int indexToSet, T item) {
        synchronized (this) {
            requireNonNull(item);
            checkElementIndex(indexToSet);

            int hash = item.hashCode();
            int existingSlot = index.findSlot(item, hash);
            if (existingSlot >= 0 && existingSlot != hashSlots[indexToSet]) {
                throw new IllegalArgumentException("An equal item is already present");
            }

            T previous = (T) elements[indexToSet];
            if (existingSlot >= 0) {
                index.replaceKeyAtSlot(existingSlot, item, hash);
                elements[indexToSet] = item;
                elementHashes[indexToSet] = hash;
            } else {
                index.removeAtSlot(hashSlots[indexToSet]);
                int newSlot = index.putAbsent(item, hash, indexToSet);
                elements[indexToSet] = item;
                elementHashes[indexToSet] = hash;
                hashSlots[indexToSet] = newSlot;
            }
            return previous;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int indexToRemove) {
        synchronized (this) {
            checkElementIndex(indexToRemove);
            return (T) removeAtInternal(indexToRemove);
        }
    }

    public boolean remove(T item) {
        synchronized (this) {
            requireNonNull(item);
            int hash = item.hashCode();
            int slot = index.findSlot(item, hash);
            if (slot < 0) {
                return false;
            }
            removeAtInternal(index.valueAtSlot(slot));
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (this) {
            requireNonNull(item);
            return index.findSlot(item, item.hashCode()) >= 0;
        }
    }

    public int indexOf(T item) {
        synchronized (this) {
            requireNonNull(item);
            int slot = index.findSlot(item, item.hashCode());
            return slot < 0 ? -1 : index.valueAtSlot(slot);
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

    private Object removeAtInternal(int position) {
        Object removed = elements[position];
        index.removeAtSlot(hashSlots[position]);

        for (int i = position; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            elementHashes[i] = elementHashes[i + 1];
            hashSlots[i] = hashSlots[i + 1];
            index.setValueAtSlot(hashSlots[i], i);
        }

        size--;
        elements[size] = null;
        elementHashes[size] = 0;
        hashSlots[size] = 0;
        return removed;
    }

    private void ensureArrayCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int newCapacity = elements.length;
        while (newCapacity < required) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                throw new OutOfMemoryError("Required array size too large");
            }
            newCapacity *= 2;
        }

        Object[] newElements = new Object[newCapacity];
        int[] newHashes = new int[newCapacity];
        int[] newSlots = new int[newCapacity];
        for (int i = 0; i < size; i++) {
            newElements[i] = elements[i];
            newHashes[i] = elementHashes[i];
            newSlots[i] = hashSlots[i];
        }

        elements = newElements;
        elementHashes = newHashes;
        hashSlots = newSlots;
    }

    private void ensureHashCapacityForInsert() {
        if (!index.needsRehashForInsert()) {
            return;
        }

        int newCapacity = index.capacityForInsert();
        int[] rebuiltSlots = index.rehash(newCapacity);
        for (int i = 0; i < size; i++) {
            hashSlots[i] = rebuiltSlots[i];
        }
    }

    private void checkElementIndex(int position) {
        if (position < 0 || position >= size) {
            throw new IndexOutOfBoundsException(
                "Index: " + position + ", Size: " + size);
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static final class HashIndex {
        private Object[] keys = new Object[16];
        private int[] hashes = new int[16];
        private int[] values = new int[16];
        private byte[] states = new byte[16]; // 0: empty, 1: occupied, 2: deleted
        private int size;
        private int used;

        int findSlot(Object key, int hash) {
            int mask = keys.length - 1;
            int slot = bucket(hash, mask);
            for (int probes = 0; probes < keys.length; probes++) {
                if (states[slot] == 0) {
                    return -1;
                }
                if (states[slot] == 1
                        && hashes[slot] == hash
                        && key.equals(keys[slot])) {
                    return slot;
                }
                slot = (slot + 1) & mask;
            }
            return -1;
        }

        int putAbsent(Object key, int hash, int value) {
            int mask = keys.length - 1;
            int slot = bucket(hash, mask);
            int firstDeleted = -1;

            for (int probes = 0; probes < keys.length; probes++) {
                if (states[slot] == 0) {
                    int destination = firstDeleted >= 0 ? firstDeleted : slot;
                    if (firstDeleted < 0) {
                        used++;
                    }
                    keys[destination] = key;
                    hashes[destination] = hash;
                    values[destination] = value;
                    states[destination] = 1;
                    size++;
                    return destination;
                }
                if (states[slot] == 2 && firstDeleted < 0) {
                    firstDeleted = slot;
                }
                slot = (slot + 1) & mask;
            }

            if (firstDeleted >= 0) {
                keys[firstDeleted] = key;
                hashes[firstDeleted] = hash;
                values[firstDeleted] = value;
                states[firstDeleted] = 1;
                size++;
                return firstDeleted;
            }
            throw new IllegalStateException("Hash index has no available slot");
        }

        void removeAtSlot(int slot) {
            keys[slot] = null;
            hashes[slot] = 0;
            values[slot] = 0;
            states[slot] = 2;
            size--;
        }

        int valueAtSlot(int slot) {
            return values[slot];
        }

        void setValueAtSlot(int slot, int value) {
            values[slot] = value;
        }

        void replaceKeyAtSlot(int slot, Object key, int hash) {
            keys[slot] = key;
            hashes[slot] = hash;
        }

        boolean needsRehashForInsert() {
            return used + 1 >= keys.length - keys.length / 3;
        }

        int capacityForInsert() {
            int threshold = keys.length - keys.length / 3;
            if (size + 1 >= threshold) {
                if (keys.length > Integer.MAX_VALUE / 2) {
                    throw new OutOfMemoryError("Required hash table size too large");
                }
                return keys.length * 2;
            }
            return keys.length;
        }

        int[] rehash(int newCapacity) {
            Object[] newKeys = new Object[newCapacity];
            int[] newHashes = new int[newCapacity];
            int[] newValues = new int[newCapacity];
            byte[] newStates = new byte[newCapacity];
            int[] rebuiltSlots = new int[size];
            int mask = newCapacity - 1;

            for (int oldSlot = 0; oldSlot < keys.length; oldSlot++) {
                if (states[oldSlot] == 1) {
                    int hash = hashes[oldSlot];
                    int newSlot = bucket(hash, mask);
                    while (newStates[newSlot] == 1) {
                        newSlot = (newSlot + 1) & mask;
                    }

                    newKeys[newSlot] = keys[oldSlot];
                    newHashes[newSlot] = hash;
                    newValues[newSlot] = values[oldSlot];
                    newStates[newSlot] = 1;
                    rebuiltSlots[values[oldSlot]] = newSlot;
                }
            }

            keys = newKeys;
            hashes = newHashes;
            values = newValues;
            states = newStates;
            used = size;
            return rebuiltSlots;
        }

        private static int bucket(int hash, int mask) {
            int spread = hash ^ (hash >>> 16);
            return spread & mask;
        }
    }
}
