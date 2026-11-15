@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[level];
        }
    }

    private final Object mutex = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private long randomState;
    private int size;

    public SkipListMap() {
        randomState = System.nanoTime()
                ^ ((long) System.identityHashCode(this) << 32);
        if (randomState == 0) {
            randomState = 0x9E3779B97F4A7C15L;
        }
    }

    public V get(K key) {
        requireKey(key);
        synchronized (mutex) {
            Node<K, V> node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (mutex) {
            return findNode(key) != null;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        synchronized (mutex) {
            Node<K, V>[] update = newNodeArray();
            Node<K, V> current = head;

            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            current = current.next[0];
            if (current != null && current.key.compareTo(key) == 0) {
                V oldValue = current.value;
                current.value = value;
                return oldValue;
            }

            Node<K, V> node = new Node<K, V>(key, value, randomLevel());
            for (int level = 0; level < node.next.length; level++) {
                node.next[level] = update[level].next[level];
                update[level].next[level] = node;
            }
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (mutex) {
            Node<K, V>[] update = newNodeArray();
            Node<K, V> current = head;

            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            current = current.next[0];
            if (current == null || current.key.compareTo(key) != 0) {
                return null;
            }

            for (int level = 0; level < current.next.length; level++) {
                update[level].next[level] = current.next[level];
            }
            size--;
            return current.value;
        }
    }

    public int size() {
        synchronized (mutex) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (mutex) {
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            size = 0;
        }
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> current = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].key.compareTo(key) < 0) {
                current = current.next[level];
            }
        }

        current = current.next[0];
        return current != null && current.key.compareTo(key) == 0
                ? current : null;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] newNodeArray() {
        return (Node<K, V>[]) new Node[MAX_LEVEL];
    }

    private int randomLevel() {
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;

        int level = 1;
        while (level < MAX_LEVEL && (value & 1L) == 0) {
            level++;
            value >>>= 1;
        }
        return level;
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }
}
