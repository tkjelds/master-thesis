@DictionaryBag.ThreadSafe
public final class DictionaryBag<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private KeyNode<K, V>[] buckets;
    private int resizeThreshold;
    private int keyCount;
    private long size;

    @SuppressWarnings("unchecked")
    public DictionaryBag() {
        buckets = (KeyNode<K, V>[]) new KeyNode[DEFAULT_CAPACITY];
        resizeThreshold = DEFAULT_CAPACITY - (DEFAULT_CAPACITY >>> 2);
    }

    public void add(K key, V value) {
        synchronized (lock) {
            if (size == Long.MAX_VALUE) {
                throw new IllegalStateException("DictionaryBag size limit reached");
            }

            int hash = hash(key);
            int index = indexFor(hash, buckets.length);
            KeyNode<K, V> keyNode = findKeyNode(key, hash, index);

            if (keyNode != null) {
                ValueNode<V> valueNode = findValueNode(keyNode.values, value);
                if (valueNode != null) {
                    if (valueNode.count == Long.MAX_VALUE) {
                        throw new IllegalStateException("Value count limit reached");
                    }
                    valueNode.count++;
                } else {
                    keyNode.values = new ValueNode<>(value, keyNode.values);
                }
                size++;
                return;
            }

            if (keyCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("DictionaryBag key limit reached");
            }

            if (keyCount + 1 > resizeThreshold && buckets.length < MAXIMUM_CAPACITY) {
                resize();
                index = indexFor(hash, buckets.length);
            }

            buckets[index] = new KeyNode<>(key, hash, new ValueNode<>(value, 1L), buckets[index]);
            keyCount++;
            size++;
        }
    }

    public boolean remove(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, buckets.length);
            KeyNode<K, V> keyNode = findKeyNode(key, hash, index);

            if (keyNode == null) {
                return false;
            }

            ValueNode<V> previousValue = null;
            ValueNode<V> valueNode = keyNode.values;
            while (valueNode != null && !valuesEqual(valueNode.value, value)) {
                previousValue = valueNode;
                valueNode = valueNode.next;
            }

            if (valueNode == null) {
                return false;
            }

            if (valueNode.count > 1) {
                valueNode.count--;
            } else if (previousValue == null) {
                keyNode.values = valueNode.next;
            } else {
                previousValue.next = valueNode.next;
            }

            size--;
            if (keyNode.values == null) {
                unlinkKeyNode(keyNode, index);
            }
            return true;
        }
    }

    public long removeAll(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, buckets.length);
            KeyNode<K, V> keyNode = findKeyNode(key, hash, index);

            if (keyNode == null) {
                return 0L;
            }

            ValueNode<V> previousValue = null;
            ValueNode<V> valueNode = keyNode.values;
            while (valueNode != null && !valuesEqual(valueNode.value, value)) {
                previousValue = valueNode;
                valueNode = valueNode.next;
            }

            if (valueNode == null) {
                return 0L;
            }

            long removed = valueNode.count;
            if (previousValue == null) {
                keyNode.values = valueNode.next;
            } else {
                previousValue.next = valueNode.next;
            }

            size -= removed;
            if (keyNode.values == null) {
                unlinkKeyNode(keyNode, index);
            }
            return removed;
        }
    }

    public long removeKey(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, buckets.length);
            KeyNode<K, V> keyNode = findKeyNode(key, hash, index);

            if (keyNode == null) {
                return 0L;
            }

            long removed = 0L;
            for (ValueNode<V> valueNode = keyNode.values; valueNode != null; valueNode = valueNode.next) {
                removed += valueNode.count;
            }

            unlinkKeyNode(keyNode, index);
            size -= removed;
            return removed;
        }
    }

    public boolean contains(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            KeyNode<K, V> keyNode = findKeyNode(key, hash, indexFor(hash, buckets.length));
            return keyNode != null && findValueNode(keyNode.values, value) != null;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            int hash = hash(key);
            return findKeyNode(key, hash, indexFor(hash, buckets.length)) != null;
        }
    }

    public long count(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            KeyNode<K, V> keyNode = findKeyNode(key, hash, indexFor(hash, buckets.length));
            if (keyNode == null) {
                return 0L;
            }

            ValueNode<V> valueNode = findValueNode(keyNode.values, value);
            return valueNode == null ? 0L : valueNode.count;
        }
    }

    public long countForKey(K key) {
        synchronized (lock) {
            int hash = hash(key);
            KeyNode<K, V> keyNode = findKeyNode(key, hash, indexFor(hash, buckets.length));
            if (keyNode == null) {
                return 0L;
            }

            long total = 0L;
            for (ValueNode<V> valueNode = keyNode.values; valueNode != null; valueNode = valueNode.next) {
                total += valueNode.count;
            }
            return total;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }

    public int keyCount() {
        synchronized (lock) {
            return keyCount;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0L;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            keyCount = 0;
            size = 0L;
        }
    }

    private KeyNode<K, V> findKeyNode(K key, int hash, int index) {
        for (KeyNode<K, V> node = buckets[index]; node != null; node = node.next) {
            if (node.hash == hash && keysEqual(node.key, key)) {
                return node;
            }
        }
        return null;
    }

    private ValueNode<V> findValueNode(ValueNode<V> head, V value) {
        for (ValueNode<V> node = head; node != null; node = node.next) {
            if (valuesEqual(node.value, value)) {
                return node;
            }
        }
        return null;
    }

    private void unlinkKeyNode(KeyNode<K, V> target, int index) {
        KeyNode<K, V> previous = null;
        KeyNode<K, V> node = buckets[index];

        while (node != null && node != target) {
            previous = node;
            node = node.next;
        }

        if (node != null) {
            if (previous == null) {
                buckets[index] = node.next;
            } else {
                previous.next = node.next;
            }
            keyCount--;
        }
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        int newCapacity = buckets.length << 1;
        KeyNode<K, V>[] newBuckets = (KeyNode<K, V>[]) new KeyNode[newCapacity];

        for (KeyNode<K, V> head : buckets) {
            KeyNode<K, V> node = head;
            while (node != null) {
                KeyNode<K, V> next = node.next;
                int index = indexFor(node.hash, newCapacity);
                node.next = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        resizeThreshold = newCapacity - (newCapacity >>> 2);
    }

    private static int hash(Object key) {
        int hash = key == null ? 0 : key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int indexFor(int hash, int capacity) {
        return hash & (capacity - 1);
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static boolean valuesEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static final class KeyNode<K, V> {
        private final K key;
        private final int hash;
        private ValueNode<V> values;
        private KeyNode<K, V> next;

        private KeyNode(K key, int hash, ValueNode<V> values, KeyNode<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.values = values;
            this.next = next;
        }
    }

    private static final class ValueNode<V> {
        private final V value;
        private long count;
        private ValueNode<V> next;

        private ValueNode(V value, long count) {
            this.value = value;
            this.count = count;
        }
    }
}
