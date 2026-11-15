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

    public static final class Entry<K, V> {
        public final K key;
        public final V value;

        private Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }
    }

    private final Object monitor = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int currentLevel = 1;
    private int size;
    private long randomState = System.nanoTime() ^ 0x9E3779B97F4A7C15L;

    public SkipListMap() {
        if (randomState == 0L) {
            randomState = 0x2545F4914F6CDD1DL;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        synchronized (monitor) {
            Node<K, V>[] update = newNodeArray();
            Node<K, V> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<K, V> found = current.next[0];
            if (found != null && found.key.compareTo(key) == 0) {
                V previous = found.value;
                found.value = value;
                return previous;
            }

            int nodeLevel = randomLevel();
            if (nodeLevel > currentLevel) {
                for (int level = currentLevel; level < nodeLevel; level++) {
                    update[level] = head;
                }
                currentLevel = nodeLevel;
            }

            Node<K, V> inserted = new Node<K, V>(key, value, nodeLevel);
            for (int level = 0; level < nodeLevel; level++) {
                inserted.next[level] = update[level].next[level];
                update[level].next[level] = inserted;
            }
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);
        synchronized (monitor) {
            Node<K, V> node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    public Entry<K, V> getEntry(K key) {
        requireKey(key);
        synchronized (monitor) {
            Node<K, V> node = findNode(key);
            return node == null ? null : new Entry<K, V>(node.key, node.value);
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (monitor) {
            return findNode(key) != null;
        }
    }

    public boolean containsValue(Object value) {
        synchronized (monitor) {
            Node<K, V> current = head.next[0];
            while (current != null) {
                if (value == null ? current.value == null : value.equals(current.value)) {
                    return true;
                }
                current = current.next[0];
            }
            return false;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (monitor) {
            Node<K, V>[] update = newNodeArray();
            Node<K, V> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<K, V> found = current.next[0];
            if (found == null || found.key.compareTo(key) != 0) {
                return null;
            }

            for (int level = 0; level < found.next.length; level++) {
                if (update[level].next[level] == found) {
                    update[level].next[level] = found.next[level];
                }
            }

            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            size--;
            return found.value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public Entry<K, V> firstEntry() {
        synchronized (monitor) {
            Node<K, V> first = head.next[0];
            return first == null ? null : new Entry<K, V>(first.key, first.value);
        }
    }

    public Entry<K, V> lastEntry() {
        synchronized (monitor) {
            Node<K, V> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null) {
                    current = current.next[level];
                }
            }
            return current == head
                    ? null
                    : new Entry<K, V>(current.key, current.value);
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            currentLevel = 1;
            size = 0;
        }
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> current = head;
        for (int level = currentLevel - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].key.compareTo(key) < 0) {
                current = current.next[level];
            }
        }

        Node<K, V> candidate = current.next[0];
        return candidate != null && candidate.key.compareTo(key) == 0
                ? candidate
                : null;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] newNodeArray() {
        return (Node<K, V>[]) new Node[MAX_LEVEL];
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && (nextRandomLong() & 1L) == 0L) {
            level++;
        }
        return level;
    }

    private long nextRandomLong() {
        long value = randomState;
        value ^= value >>> 12;
        value ^= value << 25;
        value ^= value >>> 27;
        randomState = value;
        return value * 0x2545F4914F6CDD1DL;
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }
}
