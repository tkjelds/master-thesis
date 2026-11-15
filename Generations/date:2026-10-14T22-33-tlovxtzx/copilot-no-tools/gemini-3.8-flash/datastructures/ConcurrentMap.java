@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}


    /*
     * Synchronization and Concurrency Policy:
     * - Thread-safety is achieved by synchronizing all state-accessing operations
     *   on the intrinsic lock of the ConcurrentMap instance ('this').
     * - Method declarations do not use the synchronized modifier; synchronized(this)
     *   blocks are used within method bodies.
     * - Field visibility and safe publication are guaranteed by declared volatile
     *   and final fields, combined with mutual exclusion on the instance monitor.
     * - When bounded, if the map is full (size >= maxCapacity) and a new key is being inserted:
     *     - put(k, v) waits until space becomes available.
     *     - putBlocking(k, v) waits and propagates InterruptedException.
     *     - offer(k, v) returns false immediately without blocking.
     *   Replacing the value of an existing key does not increase size and never blocks.
     * - take(k) blocks until the requested key is present in the map, then removes and returns it.
     * - All waiting operations execute within while loops guarding wait() calls,
     *   and all operations that modify the map state call notifyAll() to wake waiters.
     * - Null keys and values are rejected with NullPointerException.
     */

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;

    private final int maxCapacity;
    private volatile Node<K, V>[] table;
    private volatile int size;
    private volatile int threshold;

    private static final class Node<K, V> {
        private final int hash;
        private final K key;
        private volatile V value;
        private volatile Node<K, V> next;

        private Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    public ConcurrentMap() {
        this(DEFAULT_INITIAL_CAPACITY, MAXIMUM_CAPACITY);
    }

    public ConcurrentMap(int capacity) {
        this(capacity, capacity);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity, int maxCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive: " + initialCapacity);
        }
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Maximum capacity must be positive: " + maxCapacity);
        }
        if (initialCapacity > maxCapacity) {
            initialCapacity = maxCapacity;
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }

        this.maxCapacity = maxCapacity;
        this.table = (Node<K, V>[]) new Node[capacity];
        this.threshold = (int) (capacity * DEFAULT_LOAD_FACTOR);
        if (this.threshold <= 0) {
            this.threshold = 1;
        }
        this.size = 0;
    }

    public V put(K key, V value) {
        checkNotNull(key, value);
        int h = hash(key);

        synchronized (this) {
            while (size >= maxCapacity && findNode(key, h) == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return insertOrUpdate(key, value, h);
        }
    }

    public V putBlocking(K key, V value) throws InterruptedException {
        checkNotNull(key, value);
        int h = hash(key);

        synchronized (this) {
            while (size >= maxCapacity && findNode(key, h) == null) {
                wait();
            }
            return insertOrUpdate(key, value, h);
        }
    }

    public boolean offer(K key, V value) {
        checkNotNull(key, value);
        int h = hash(key);

        synchronized (this) {
            if (size >= maxCapacity && findNode(key, h) == null) {
                return false;
            }
            insertOrUpdate(key, value, h);
            return true;
        }
    }

    public V putIfAbsent(K key, V value) {
        checkNotNull(key, value);
        int h = hash(key);

        synchronized (this) {
            Node<K, V> existing = findNode(key, h);
            if (existing != null) {
                return existing.value;
            }

            while (size >= maxCapacity) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            existing = findNode(key, h);
            if (existing != null) {
                return existing.value;
            }

            insertOrUpdate(key, value, h);
            return null;
        }
    }

    public V get(Object key) {
        if (key == null) {
            return null;
        }
        int h = hash(key);

        synchronized (this) {
            Node<K, V> node = findNode(key, h);
            return node == null ? null : node.value;
        }
    }

    public V getOrDefault(Object key, V defaultValue) {
        if (key == null) {
            return defaultValue;
        }
        int h = hash(key);

        synchronized (this) {
            Node<K, V> node = findNode(key, h);
            return node == null ? defaultValue : node.value;
        }
    }

    public boolean containsKey(Object key) {
        if (key == null) {
            return false;
        }
        int h = hash(key);

        synchronized (this) {
            return findNode(key, h) != null;
        }
    }

    public boolean containsValue(Object value) {
        if (value == null) {
            return false;
        }

        synchronized (this) {
            for (int i = 0; i < table.length; i++) {
                Node<K, V> current = table[i];
                while (current != null) {
                    if (valuesEqual(current.value, value)) {
                        return true;
                    }
                    current = current.next;
                }
            }
            return false;
        }
    }

    public V remove(Object key) {
        if (key == null) {
            return null;
        }
        int h = hash(key);

        synchronized (this) {
            int index = indexFor(h, table.length);
            Node<K, V> previous = null;
            Node<K, V> current = table[index];

            while (current != null) {
                if (current.hash == h && keysEqual(current.key, key)) {
                    if (previous == null) {
                        table[index] = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    size--;
                    notifyAll();
                    return current.value;
                }
                previous = current;
                current = current.next;
            }
            return null;
        }
    }

    public boolean remove(Object key, Object value) {
        if (key == null || value == null) {
            return false;
        }
        int h = hash(key);

        synchronized (this) {
            int index = indexFor(h, table.length);
            Node<K, V> previous = null;
            Node<K, V> current = table[index];

            while (current != null) {
                if (current.hash == h && keysEqual(current.key, key)) {
                    if (valuesEqual(current.value, value)) {
                        if (previous == null) {
                            table[index] = current.next;
                        } else {
                            previous.next = current.next;
                        }
                        size--;
                        notifyAll();
                        return true;
                    }
                    return false;
                }
                previous = current;
                current = current.next;
            }
            return false;
        }
    }

    public boolean replace(K key, V oldValue, V newValue) {
        checkNotNull(key, newValue);
        if (oldValue == null) {
            return false;
        }
        int h = hash(key);

        synchronized (this) {
            Node<K, V> node = findNode(key, h);
            if (node != null && valuesEqual(node.value, oldValue)) {
                node.value = newValue;
                notifyAll();
                return true;
            }
            return false;
        }
    }

    public V replace(K key, V value) {
        checkNotNull(key, value);
        int h = hash(key);

        synchronized (this) {
            Node<K, V> node = findNode(key, h);
            if (node != null) {
                V previous = node.value;
                node.value = value;
                notifyAll();
                return previous;
            }
            return null;
        }
    }

    public V take(Object key) throws InterruptedException {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
        int h = hash(key);

        synchronized (this) {
            while (true) {
                int index = indexFor(h, table.length);
                Node<K, V> previous = null;
                Node<K, V> current = table[index];

                while (current != null) {
                    if (current.hash == h && keysEqual(current.key, key)) {
                        if (previous == null) {
                            table[index] = current.next;
                        } else {
                            previous.next = current.next;
                        }
                        size--;
                        notifyAll();
                        return current.value;
                    }
                    previous = current;
                    current = current.next;
                }
                wait();
            }
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public int capacity() {
        return maxCapacity;
    }

    public int remainingCapacity() {
        synchronized (this) {
            return maxCapacity - size;
        }
    }

    @SuppressWarnings("unchecked")
    public void clear() {
        synchronized (this) {
            int capacity = table.length;
            table = (Node<K, V>[]) new Node[capacity];
            size = 0;
            notifyAll();
        }
    }

    public Object[] keys() {
        synchronized (this) {
            Object[] result = new Object[size];
            int index = 0;
            for (int i = 0; i < table.length; i++) {
                Node<K, V> current = table[i];
                while (current != null && index < result.length) {
                    result[index++] = current.key;
                    current = current.next;
                }
            }
            return result;
        }
    }

    public Object[] values() {
        synchronized (this) {
            Object[] result = new Object[size];
            int index = 0;
            for (int i = 0; i < table.length; i++) {
                Node<K, V> current = table[i];
                while (current != null && index < result.length) {
                    result[index++] = current.value;
                    current = current.next;
                }
            }
            return result;
        }
    }

    private V insertOrUpdate(K key, V value, int h) {
        int index = indexFor(h, table.length);
        Node<K, V> current = table[index];

        while (current != null) {
            if (current.hash == h && keysEqual(current.key, key)) {
                V previous = current.value;
                current.value = value;
                notifyAll();
                return previous;
            }
            current = current.next;
        }

        table[index] = new Node<K, V>(h, key, value, table[index]);
        size++;

        if (size > threshold && table.length < MAXIMUM_CAPACITY && table.length < maxCapacity) {
            resize();
        }

        notifyAll();
        return null;
    }

    private Node<K, V> findNode(Object key, int h) {
        int index = indexFor(h, table.length);
        Node<K, V> current = table[index];
        while (current != null) {
            if (current.hash == h && keysEqual(current.key, key)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        int oldCapacity = table.length;
        int newCapacity = oldCapacity << 1;

        if (newCapacity <= 0 || newCapacity > MAXIMUM_CAPACITY) {
            newCapacity = MAXIMUM_CAPACITY;
        }
        if (newCapacity > maxCapacity) {
            newCapacity = maxCapacity;
        }
        if (newCapacity <= oldCapacity) {
            return;
        }

        Node<K, V>[] newTable = (Node<K, V>[]) new Node[newCapacity];
        for (int i = 0; i < oldCapacity; i++) {
            Node<K, V> current = table[i];
            while (current != null) {
                Node<K, V> next = current.next;
                int newIndex = indexFor(current.hash, newCapacity);
                current.next = newTable[newIndex];
                newTable[newIndex] = current;
                current = next;
            }
        }

        table = newTable;
        threshold = (int) (newCapacity * DEFAULT_LOAD_FACTOR);
        if (threshold <= 0) {
            threshold = 1;
        }
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int indexFor(int hash, int capacity) {
        return hash & (capacity - 1);
    }

    private static boolean keysEqual(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    private static boolean valuesEqual(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    private static void checkNotNull(Object key, Object value) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
        if (value == null) {
            throw new NullPointerException("Value must not be null");
        }
    }
}
