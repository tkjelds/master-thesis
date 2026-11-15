@DictionaryBag.ThreadSafe
public class DictionaryBag<K, V> {
    @interface ThreadSafe {}


    public static final class Entry<K, V> {
        private final K key;
        private V value;

        public Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public void setValue(V value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "Entry[" + key + "=" + value + "]";
        }
    }

    private final Object lock;
    private final int capacity;
    private Entry<K, V>[] entries;
    private int size;

    public DictionaryBag() {
        this(16);
    }

    public DictionaryBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        this.capacity = capacity;
        this.lock = new Object();
        this.entries = new Entry[capacity];
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size >= capacity;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < entries.length; i++) {
                entries[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            return indexOfKeyLocked(key) >= 0;
        }
    }

    public boolean containsValue(V value) {
        synchronized (lock) {
            for (int i = 0; i < entries.length; i++) {
                Entry<K, V> entry = entries[i];
                if (entry != null && valuesEqual(entry.value, value)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V get(K key) {
        synchronized (lock) {
            int index = indexOfKeyLocked(key);
            if (index < 0) {
                return null;
            }
            return entries[index].value;
        }
    }

    public V put(K key, V value) throws InterruptedException {
        synchronized (lock) {
            while (size >= capacity && indexOfKeyLocked(key) < 0) {
                lock.wait();
            }
            int index = indexOfKeyLocked(key);
            if (index >= 0) {
                V previous = entries[index].value;
                entries[index].value = value;
                lock.notifyAll();
                return previous;
            }
            int freeIndex = findFreeIndexLocked();
            entries[freeIndex] = new Entry<K, V>(key, value);
            size++;
            lock.notifyAll();
            return null;
        }
    }

    public boolean offer(K key, V value) {
        synchronized (lock) {
            int index = indexOfKeyLocked(key);
            if (index >= 0) {
                entries[index].value = value;
                lock.notifyAll();
                return true;
            }
            if (size >= capacity) {
                return false;
            }
            int freeIndex = findFreeIndexLocked();
            entries[freeIndex] = new Entry<K, V>(key, value);
            size++;
            lock.notifyAll();
            return true;
        }
    }

    public boolean add(K key, V value) {
        return offer(key, value);
    }

    public V putIfAbsent(K key, V value) throws InterruptedException {
        synchronized (lock) {
            while (size >= capacity && indexOfKeyLocked(key) < 0) {
                lock.wait();
            }
            int index = indexOfKeyLocked(key);
            if (index >= 0) {
                return entries[index].value;
            }
            int freeIndex = findFreeIndexLocked();
            entries[freeIndex] = new Entry<K, V>(key, value);
            size++;
            lock.notifyAll();
            return null;
        }
    }

    public V remove(K key) {
        synchronized (lock) {
            int index = indexOfKeyLocked(key);
            if (index < 0) {
                return null;
            }
            Entry<K, V> entry = entries[index];
            V value = entry.value;
            entries[index] = null;
            size--;
            lock.notifyAll();
            return value;
        }
    }

    public V take(K key) throws InterruptedException {
        synchronized (lock) {
            while (indexOfKeyLocked(key) < 0) {
                lock.wait();
            }
            int index = indexOfKeyLocked(key);
            Entry<K, V> entry = entries[index];
            V value = entry.value;
            entries[index] = null;
            size--;
            lock.notifyAll();
            return value;
        }
    }

    public Entry<K, V> takeEntry() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            int index = findFirstEntryIndexLocked();
            Entry<K, V> entry = entries[index];
            entries[index] = null;
            size--;
            lock.notifyAll();
            return entry;
        }
    }

    public Entry<K, V> pollEntry() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int index = findFirstEntryIndexLocked();
            Entry<K, V> entry = entries[index];
            entries[index] = null;
            size--;
            lock.notifyAll();
            return entry;
        }
    }

    public V take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            int index = findFirstEntryIndexLocked();
            Entry<K, V> entry = entries[index];
            entries[index] = null;
            size--;
            lock.notifyAll();
            return entry.value;
        }
    }

    public V poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int index = findFirstEntryIndexLocked();
            Entry<K, V> entry = entries[index];
            entries[index] = null;
            size--;
            lock.notifyAll();
            return entry.value;
        }
    }

    public boolean replace(K key, V value) {
        synchronized (lock) {
            int index = indexOfKeyLocked(key);
            if (index < 0) {
                return false;
            }
            entries[index].value = value;
            lock.notifyAll();
            return true;
        }
    }

    public boolean replaceIfValue(K key, V expectedValue, V newValue) {
        synchronized (lock) {
            int index = indexOfKeyLocked(key);
            if (index < 0) {
                return false;
            }
            if (!valuesEqual(entries[index].value, expectedValue)) {
                return false;
            }
            entries[index].value = newValue;
            lock.notifyAll();
            return true;
        }
    }

    public V insertOrUpdate(K key, V value) throws InterruptedException {
        return put(key, value);
    }

    private int findFirstEntryIndexLocked() {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] != null) {
                return i;
            }
        }
        return -1;
    }

    private int findFreeIndexLocked() {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfKeyLocked(K key) {
        for (int i = 0; i < entries.length; i++) {
            Entry<K, V> entry = entries[i];
            if (entry != null && keyEquals(entry.key, key)) {
                return i;
            }
        }
        return -1;
    }

    private boolean keyEquals(K left, K right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private boolean valuesEqual(V left, V right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }
}
