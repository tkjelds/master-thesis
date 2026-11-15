@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = Integer.MAX_VALUE;

    private final Object lock;
    private final int capacity;
    private final boolean bounded;

    private Entry<K, V>[] table;
    private int size;

    public ConcurrentMap() {
        this(MAX_CAPACITY);
    }

    public ConcurrentMap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.lock = new Object();
        this.capacity = capacity;
        this.bounded = capacity != MAX_CAPACITY;
        int initialCapacity = DEFAULT_INITIAL_CAPACITY;
        if (capacity < initialCapacity) {
            initialCapacity = capacity;
        }
        this.table = (Entry<K, V>[]) new Entry[initialCapacity];
    }

    private static final class Entry<K, V> {
        private final K key;
        private V value;

        private Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
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
            return bounded && size >= capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            if (!bounded) {
                return MAX_CAPACITY - size;
            }
            return capacity - size;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            return findIndex(key) >= 0;
        }
    }

    public boolean containsValue(V value) {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                Entry<K, V> entry = table[i];
                if (entry != null && equal(entry.value, value)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V get(K key) {
        synchronized (lock) {
            int index = findIndex(key);
            if (index < 0) {
                return null;
            }
            return table[index].value;
        }
    }

    public V put(K key, V value) {
        synchronized (lock) {
            while (bounded && size >= capacity) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            int index = findIndex(key);
            if (index >= 0) {
                V oldValue = table[index].value;
                table[index].value = value;
                lock.notifyAll();
                return oldValue;
            }

            ensureCapacityForInsert();
            for (int i = 0; i < table.length; i++) {
                if (table[i] == null) {
                    table[i] = new Entry<K, V>(key, value);
                    size++;
                    lock.notifyAll();
                    return null;
                }
            }

            throw new IllegalStateException("Map insertion failed unexpectedly");
        }
    }

    public V putIfAbsent(K key, V value) {
        synchronized (lock) {
            while (bounded && size >= capacity) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            int index = findIndex(key);
            if (index >= 0) {
                return table[index].value;
            }

            ensureCapacityForInsert();
            for (int i = 0; i < table.length; i++) {
                if (table[i] == null) {
                    table[i] = new Entry<K, V>(key, value);
                    size++;
                    lock.notifyAll();
                    return null;
                }
            }

            throw new IllegalStateException("Map insertion failed unexpectedly");
        }
    }

    public V remove(K key) {
        synchronized (lock) {
            int index = findIndex(key);
            if (index < 0) {
                return null;
            }
            V oldValue = table[index].value;
            table[index] = null;
            size--;
            lock.notifyAll();
            return oldValue;
        }
    }

    public boolean remove(K key, V expectedValue) {
        synchronized (lock) {
            int index = findIndex(key);
            if (index < 0) {
                return false;
            }
            if (!equal(table[index].value, expectedValue)) {
                return false;
            }
            table[index] = null;
            size--;
            lock.notifyAll();
            return true;
        }
    }

    public V take() {
        synchronized (lock) {
            while (size == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            int index = firstOccupiedIndex();
            if (index < 0) {
                return null;
            }
            V oldValue = table[index].value;
            table[index] = null;
            size--;
            lock.notifyAll();
            return oldValue;
        }
    }

    public V poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int index = firstOccupiedIndex();
            if (index < 0) {
                return null;
            }
            V oldValue = table[index].value;
            table[index] = null;
            size--;
            lock.notifyAll();
            return oldValue;
        }
    }

    public V peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int index = firstOccupiedIndex();
            if (index < 0) {
                return null;
            }
            return table[index].value;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    public Object[] keySet() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int position = 0;
            for (int i = 0; i < table.length && position < result.length; i++) {
                Entry<K, V> entry = table[i];
                if (entry != null) {
                    result[position++] = entry.key;
                }
            }
            return result;
        }
    }

    public Object[] values() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int position = 0;
            for (int i = 0; i < table.length && position < result.length; i++) {
                Entry<K, V> entry = table[i];
                if (entry != null) {
                    result[position++] = entry.value;
                }
            }
            return result;
        }
    }

    public boolean isBounded() {
        return bounded;
    }

    public int capacity() {
        return capacity;
    }

    private void ensureCapacityForInsert() {
        if (size >= table.length) {
            int newCapacity = table.length;
            if (newCapacity >= MAX_CAPACITY / 2) {
                newCapacity = MAX_CAPACITY;
            } else {
                newCapacity = newCapacity * 2;
            }
            if (bounded && newCapacity > capacity) {
                newCapacity = capacity;
            }
            if (newCapacity <= table.length) {
                newCapacity = table.length + 1;
            }
            if (newCapacity > MAX_CAPACITY) {
                newCapacity = MAX_CAPACITY;
            }
            Entry<K, V>[] newTable = (Entry<K, V>[]) new Entry[newCapacity];
            for (int i = 0; i < table.length; i++) {
                Entry<K, V> entry = table[i];
                if (entry != null) {
                    newTable[i] = entry;
                }
            }
            table = newTable;
        }
    }

    private int findIndex(Object key) {
        for (int i = 0; i < table.length; i++) {
            Entry<K, V> entry = table[i];
            if (entry != null && equal(entry.key, key)) {
                return i;
            }
        }
        return -1;
    }

    private int firstOccupiedIndex() {
        for (int i = 0; i < table.length; i++) {
            if (table[i] != null) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equal(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    public String toString() {
        synchronized (lock) {
            StringBuilder builder = new StringBuilder();
            builder.append("{");
            boolean first = true;
            for (int i = 0; i < table.length; i++) {
                Entry<K, V> entry = table[i];
                if (entry != null) {
                    if (!first) {
                        builder.append(", ");
                    }
                    first = false;
                    builder.append(String.valueOf(entry.key));
                    builder.append("=");
                    builder.append(String.valueOf(entry.value));
                }
            }
            builder.append("}");
            return builder.toString();
        }
    }
}
