@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_MAX_LEVEL = 32;

    private final Object lock = new Object();
    private final int maxLevel;
    private final Node<K, V> head;

    // These fields are accessed only while holding lock.
    private int currentLevel;
    private int size;
    private int randomState = 0x6D2B79F5;

    private static final class Node<K, V> {
        private final K key;
        private V value;
        private final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        private Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[level + 1];
        }
    }

    public SkipListMap() {
        this(DEFAULT_MAX_LEVEL);
    }

    public SkipListMap(int maxLevel) {
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel must be at least 1");
        }
        this.maxLevel = maxLevel;
        this.head = new Node<K, V>(null, null, maxLevel);
    }

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    /**
     * Associates the value with the key and returns the previous value,
     * or null if the key was not already present.
     */
    public V put(K key, V value) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V>[] predecessors = findPredecessors(key);
            Node<K, V> existing = predecessors[0].next[0];

            if (existing != null && existing.key.compareTo(key) == 0) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            int nodeLevel = randomLevel();
            if (nodeLevel > currentLevel) {
                for (int i = currentLevel + 1; i <= nodeLevel; i++) {
                    predecessors[i] = head;
                }
                currentLevel = nodeLevel;
            }

            Node<K, V> node = new Node<K, V>(key, value, nodeLevel);
            for (int i = 0; i <= nodeLevel; i++) {
                node.next[i] = predecessors[i].next[i];
                predecessors[i].next[i] = node;
            }
            size++;
            return null;
        }
    }

    /**
     * Removes the key and returns its value, or null if the key was absent.
     */
    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V>[] predecessors = findPredecessors(key);
            Node<K, V> target = predecessors[0].next[0];

            if (target == null || target.key.compareTo(key) != 0) {
                return null;
            }

            for (int i = 0; i <= currentLevel; i++) {
                if (predecessors[i].next[i] != target) {
                    break;
                }
                predecessors[i].next[i] = target.next[i];
            }

            while (currentLevel > 0 && head.next[currentLevel] == null) {
                currentLevel--;
            }
            size--;
            return target.value;
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i <= currentLevel; i++) {
                head.next[i] = null;
            }
            currentLevel = 0;
            size = 0;
        }
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> node = head;
        for (int i = currentLevel; i >= 0; i--) {
            while (node.next[i] != null && node.next[i].key.compareTo(key) < 0) {
                node = node.next[i];
            }
        }

        node = node.next[0];
        return node != null && node.key.compareTo(key) == 0 ? node : null;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] findPredecessors(K key) {
        Node<K, V>[] predecessors = (Node<K, V>[]) new Node[maxLevel + 1];
        Node<K, V> node = head;

        for (int i = currentLevel; i >= 0; i--) {
            while (node.next[i] != null && node.next[i].key.compareTo(key) < 0) {
                node = node.next[i];
            }
            predecessors[i] = node;
        }
        return predecessors;
    }

    private int randomLevel() {
        int level = 0;
        while (level < maxLevel && (nextRandomInt() & 1) == 0) {
            level++;
        }
        return level;
    }

    private int nextRandomInt() {
        int value = randomState;
        value ^= value << 13;
        value ^= value >>> 17;
        value ^= value << 5;
        randomState = value;
        return value;
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }
}
