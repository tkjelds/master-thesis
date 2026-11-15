@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] forward;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.forward = (Node<K, V>[]) new Node[level];
        }
    }

    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int currentLevel = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public SkipListMap() {
    }

    public V get(K key) {
        requireKey(key);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (this) {
            return findNode(key) != null;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        synchronized (this) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] predecessors = (Node<K, V>[]) new Node[MAX_LEVEL];

            Node<K, V> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.forward[level] != null
                        && current.forward[level].key.compareTo(key) < 0) {
                    current = current.forward[level];
                }
                predecessors[level] = current;
            }

            current = current.forward[0];
            if (current != null && current.key.compareTo(key) == 0) {
                V previous = current.value;
                current.value = value;
                return previous;
            }

            int nodeLevel = randomLevel();
            if (nodeLevel > currentLevel) {
                for (int level = currentLevel; level < nodeLevel; level++) {
                    predecessors[level] = head;
                }
                currentLevel = nodeLevel;
            }

            Node<K, V> node = new Node<K, V>(key, value, nodeLevel);
            for (int level = 0; level < nodeLevel; level++) {
                node.forward[level] = predecessors[level].forward[level];
                predecessors[level].forward[level] = node;
            }

            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (this) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] predecessors = (Node<K, V>[]) new Node[MAX_LEVEL];

            Node<K, V> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.forward[level] != null
                        && current.forward[level].key.compareTo(key) < 0) {
                    current = current.forward[level];
                }
                predecessors[level] = current;
            }

            current = current.forward[0];
            if (current == null || current.key.compareTo(key) != 0) {
                return null;
            }

            for (int level = 0; level < currentLevel; level++) {
                if (predecessors[level].forward[level] != current) {
                    break;
                }
                predecessors[level].forward[level] = current.forward[level];
            }

            while (currentLevel > 1 && head.forward[currentLevel - 1] == null) {
                currentLevel--;
            }

            size--;
            return current.value;
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

    public void clear() {
        synchronized (this) {
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.forward[level] = null;
            }
            currentLevel = 1;
            size = 0;
        }
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> current = head;
        for (int level = currentLevel - 1; level >= 0; level--) {
            while (current.forward[level] != null
                    && current.forward[level].key.compareTo(key) < 0) {
                current = current.forward[level];
            }
        }

        current = current.forward[0];
        return current != null && current.key.compareTo(key) == 0 ? current : null;
    }

    private int randomLevel() {
        int level = 1;
        long bits = nextRandom();
        while (level < MAX_LEVEL && (bits & 1L) == 0L) {
            level++;
            bits >>>= 1;
            if (bits == 0L && level < MAX_LEVEL) {
                bits = nextRandom();
            }
        }
        return level;
    }

    private long nextRandom() {
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;
        return value;
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("Key must not be null");
        }
    }
}
