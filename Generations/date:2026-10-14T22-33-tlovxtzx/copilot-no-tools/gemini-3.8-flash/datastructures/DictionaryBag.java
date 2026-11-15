@DictionaryBag.ThreadSafe
public final class DictionaryBag<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_BUCKET_COUNT = 16;
    private static final int UNBOUNDED = -1;

    private final Object lock = new Object();
    private final Entry<K, V>[] buckets;
    private final int maximumEntries;

    private int entryCount;
    private int distinctKeyCount;

    @SuppressWarnings("unchecked")
    public DictionaryBag() {
        this.buckets = (Entry<K, V>[]) new Entry[DEFAULT_BUCKET_COUNT];
        this.maximumEntries = UNBOUNDED;
    }

    @SuppressWarnings("unchecked")
    public DictionaryBag(int maximumEntries) {
        if (maximumEntries == 0 || maximumEntries < UNBOUNDED) {
            throw new IllegalArgumentException("maximumEntries must be positive or -1");
        }

        this.buckets = (Entry<K, V>[]) new Entry[DEFAULT_BUCKET_COUNT];
        this.maximumEntries = maximumEntries;
    }

    public boolean put(K key, V value) {
        synchronized (lock) {
            while (isFullLocked()) {
                try {
                    lock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            addEntryLocked(key, value);
            lock.notifyAll();
            return true;
        }
    }

    public void putBlocking(K key, V value) throws InterruptedException {
        synchronized (lock) {
            while (isFullLocked()) {
                lock.wait();
            }

            addEntryLocked(key, value);
            lock.notifyAll();
        }
    }

    public boolean add(K key, V value) {
        return put(key, value);
    }

    public boolean offer(K key, V value) {
        synchronized (lock) {
            if (isFullLocked()) {
                return false;
            }

            addEntryLocked(key, value);
            lock.notifyAll();
            return true;
        }
    }

    public Item<K, V> take() {
        synchronized (lock) {
            while (entryCount == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            for (int index = 0; index < buckets.length; index++) {
                Entry<K, V> entry = buckets[index];
                if (entry != null) {
                    buckets[index] = entry.next;
                    entryCount--;

                    if (!containsKeyLocked(index, entry.key)) {
                        distinctKeyCount--;
                    }

                    lock.notifyAll();
                    return new Item<K, V>(entry.key, entry.value);
                }
            }

            throw new IllegalStateException("DictionaryBag invariant violated");
        }
    }

    public Item<K, V> takeBlocking() throws InterruptedException {
        synchronized (lock) {
            while (entryCount == 0) {
                lock.wait();
            }

            for (int index = 0; index < buckets.length; index++) {
                Entry<K, V> entry = buckets[index];
                if (entry != null) {
                    buckets[index] = entry.next;
                    entryCount--;

                    if (!containsKeyLocked(index, entry.key)) {
                        distinctKeyCount--;
                    }

                    lock.notifyAll();
                    return new Item<K, V>(entry.key, entry.value);
                }
            }

            throw new IllegalStateException("DictionaryBag invariant violated");
        }
    }

    public Item<K, V> poll() {
        synchronized (lock) {
            if (entryCount == 0) {
                return null;
            }

            for (int index = 0; index < buckets.length; index++) {
                Entry<K, V> entry = buckets[index];
                if (entry != null) {
                    buckets[index] = entry.next;
                    entryCount--;

                    if (!containsKeyLocked(index, entry.key)) {
                        distinctKeyCount--;
                    }

                    lock.notifyAll();
                    return new Item<K, V>(entry.key, entry.value);
                }
            }

            return null;
        }
    }

    public Item<K, V> peek() {
        synchronized (lock) {
            if (entryCount == 0) {
                return null;
            }

            for (int index = 0; index < buckets.length; index++) {
                Entry<K, V> entry = buckets[index];
                if (entry != null) {
                    return new Item<K, V>(entry.key, entry.value);
                }
            }

            return null;
        }
    }

    public V get(K key) {
        synchronized (lock) {
            Entry<K, V> current = buckets[bucketIndex(key)];
            while (current != null) {
                if (equal(current.key, key)) {
                    return current.value;
                }
                current = current.next;
            }
            return null;
        }
    }

    public boolean remove(K key) {
        synchronized (lock) {
            int index = bucketIndex(key);
            Entry<K, V> previous = null;
            Entry<K, V> current = buckets[index];

            while (current != null) {
                if (equal(current.key, key)) {
                    if (previous == null) {
                        buckets[index] = current.next;
                    } else {
                        previous.next = current.next;
                    }

                    entryCount--;
                    if (!containsKeyLocked(index, key)) {
                        distinctKeyCount--;
                    }

                    lock.notifyAll();
                    return true;
                }

                previous = current;
                current = current.next;
            }

            return false;
        }
    }

    public boolean remove(K key, V value) {
        synchronized (lock) {
            int index = bucketIndex(key);
            Entry<K, V> previous = null;
            Entry<K, V> current = buckets[index];

            while (current != null) {
                if (equal(current.key, key) && equal(current.value, value)) {
                    if (previous == null) {
                        buckets[index] = current.next;
                    } else {
                        previous.next = current.next;
                    }

                    entryCount--;
                    if (!containsKeyLocked(index, key)) {
                        distinctKeyCount--;
                    }

                    lock.notifyAll();
                    return true;
                }

                previous = current;
                current = current.next;
            }

            return false;
        }
    }

    public int removeAll(K key) {
        synchronized (lock) {
            int index = bucketIndex(key);
            Entry<K, V> previous = null;
            Entry<K, V> current = buckets[index];
            int removed = 0;

            while (current != null) {
                Entry<K, V> next = current.next;
                if (equal(current.key, key)) {
                    if (previous == null) {
                        buckets[index] = next;
                    } else {
                        previous.next = next;
                    }
                    removed++;
                } else {
                    previous = current;
                }
                current = next;
            }

            if (removed != 0) {
                entryCount -= removed;
                distinctKeyCount--;
                lock.notifyAll();
            }

            return removed;
        }
    }

    public boolean contains(K key, V value) {
        synchronized (lock) {
            Entry<K, V> current = buckets[bucketIndex(key)];
            while (current != null) {
                if (equal(current.key, key) && equal(current.value, value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            return containsKeyLocked(bucketIndex(key), key);
        }
    }

    public boolean containsValue(V value) {
        synchronized (lock) {
            for (int index = 0; index < buckets.length; index++) {
                Entry<K, V> current = buckets[index];
                while (current != null) {
                    if (equal(current.value, value)) {
                        return true;
                    }
                    current = current.next;
                }
            }
            return false;
        }
    }

    public int count(K key) {
        synchronized (lock) {
            int count = 0;
            Entry<K, V> current = buckets[bucketIndex(key)];

            while (current != null) {
                if (equal(current.key, key)) {
                    count++;
                }
                current = current.next;
            }

            return count;
        }
    }

    public Object[] getAll(K key) {
        synchronized (lock) {
            int index = bucketIndex(key);
            int count = 0;
            Entry<K, V> current = buckets[index];

            while (current != null) {
                if (equal(current.key, key)) {
                    count++;
                }
                current = current.next;
            }

            Object[] values = new Object[count];
            int position = 0;
            current = buckets[index];

            while (current != null) {
                if (equal(current.key, key)) {
                    values[position++] = current.value;
                }
                current = current.next;
            }

            return values;
        }
    }

    public int size() {
        synchronized (lock) {
            return entryCount;
        }
    }

    public int distinctKeys() {
        synchronized (lock) {
            return distinctKeyCount;
        }
    }

    public int capacity() {
        synchronized (lock) {
            return maximumEntries;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            if (maximumEntries == UNBOUNDED) {
                return Integer.MAX_VALUE;
            }
            return maximumEntries - entryCount;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return entryCount == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return isFullLocked();
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int index = 0; index < buckets.length; index++) {
                buckets[index] = null;
            }

            entryCount = 0;
            distinctKeyCount = 0;
            lock.notifyAll();
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] items = new Object[entryCount];
            int position = 0;

            for (int index = 0; index < buckets.length; index++) {
                Entry<K, V> current = buckets[index];
                while (current != null) {
                    items[position++] = new Item<K, V>(current.key, current.value);
                    current = current.next;
                }
            }

            return items;
        }
    }

    public Object[] toArray() {
        return snapshot();
    }

    private void addEntryLocked(K key, V value) {
        int index = bucketIndex(key);
        if (!containsKeyLocked(index, key)) {
            distinctKeyCount++;
        }

        buckets[index] = new Entry<K, V>(key, value, buckets[index]);
        entryCount++;
    }

    private boolean containsKeyLocked(int index, K key) {
        Entry<K, V> current = buckets[index];
        while (current != null) {
            if (equal(current.key, key)) {
                return true;
            }
            current = current.next;
        }
        return false;
    }

    private boolean isFullLocked() {
        return maximumEntries != UNBOUNDED && entryCount >= maximumEntries;
    }

    private int bucketIndex(K key) {
        int hash = key == null ? 0 : key.hashCode();
        hash ^= hash >>> 16;
        return (hash & 0x7fffffff) % buckets.length;
    }

    private static boolean equal(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static final class Entry<K, V> {
        private final K key;
        private final V value;
        private Entry<K, V> next;

        private Entry(K key, V value, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    public static final class Item<K, V> {
        private final K key;
        private final V value;

        public Item(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            Item<?, ?> other = (Item<?, ?>) obj;
            return (key == other.key || (key != null && key.equals(other.key)))
                    && (value == other.value || (value != null && value.equals(other.value)));
        }

        @Override
        public int hashCode() {
            int keyHash = key == null ? 0 : key.hashCode();
            int valueHash = value == null ? 0 : value.hashCode();
            return keyHash ^ valueHash;
        }

        @Override
        public String toString() {
            return "(" + key + ", " + value + ")";
        }
    }
}
