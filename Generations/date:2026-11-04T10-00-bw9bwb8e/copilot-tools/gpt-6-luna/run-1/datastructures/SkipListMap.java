@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private final Object lock = new Object();
    private final Node head = new Node(null, null, MAX_LEVEL);
    private int level = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    private final class Node {
        private final K key;
        private V value;
        private final Node[] forward;

        private Node(K key, V value, int height) {
            this.key = key;
            this.value = value;
            this.forward = new SkipListMap.Node[height];
        }
    }

    public SkipListMap() {
    }

    // Keys and values must be non-null. Operations do not block beyond lock acquisition.
    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);

        synchronized (lock) {
            Node[] update = new SkipListMap.Node[MAX_LEVEL];
            Node current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null
                        && current.forward[i].key.compareTo(key) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }

            Node existing = update[0].forward[0];
            if (existing != null && existing.key.compareTo(key) == 0) {
                V oldValue = existing.value;
                existing.value = value;
                return oldValue;
            }

            int newLevel = randomLevel();
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    update[i] = head;
                }
                level = newLevel;
            }

            Node inserted = new Node(key, value, newLevel);
            for (int i = 0; i < newLevel; i++) {
                inserted.forward[i] = update[i].forward[i];
                update[i].forward[i] = inserted;
            }
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);

        synchronized (lock) {
            Node node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);

        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    public V remove(K key) {
        requireKey(key);

        synchronized (lock) {
            Node[] update = new SkipListMap.Node[MAX_LEVEL];
            Node current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null
                        && current.forward[i].key.compareTo(key) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }

            Node target = update[0].forward[0];
            if (target == null || target.key.compareTo(key) != 0) {
                return null;
            }

            for (int i = 0; i < level; i++) {
                if (update[i].forward[i] == target) {
                    update[i].forward[i] = target.forward[i];
                }
            }

            size--;
            while (level > 1 && head.forward[level - 1] == null) {
                level--;
            }
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

    public K firstKey() {
        synchronized (lock) {
            return head.forward[0] == null ? null : head.forward[0].key;
        }
    }

    public K lastKey() {
        synchronized (lock) {
            Node current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null) {
                    current = current.forward[i];
                }
            }
            return current == head ? null : current.key;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < level; i++) {
                head.forward[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    private Node findNode(K key) {
        Node current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.forward[i] != null
                    && current.forward[i].key.compareTo(key) < 0) {
                current = current.forward[i];
            }
        }

        Node candidate = current.forward[0];
        return candidate != null && candidate.key.compareTo(key) == 0
                ? candidate
                : null;
    }

    private int randomLevel() {
        randomState ^= randomState << 13;
        randomState ^= randomState >>> 7;
        randomState ^= randomState << 17;

        long bits = randomState;
        int result = 1;
        while (result < MAX_LEVEL && (bits & 1L) == 0L) {
            result++;
            bits >>>= 1;
            if (bits == 0L) {
                break;
            }
        }
        return result;
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }

    private void requireValue(V value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }
}
