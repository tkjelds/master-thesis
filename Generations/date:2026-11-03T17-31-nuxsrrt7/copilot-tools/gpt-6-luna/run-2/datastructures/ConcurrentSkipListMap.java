@ConcurrentSkipListMap.ThreadSafe
public final class ConcurrentSkipListMap<K extends Comparable<? super K>, V> {
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

    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int level = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public V put(K key, V value) {
        if (key == null) {
            throw new NullPointerException("key");
        }

        synchronized (this) {
            Node<K, V>[] update = new Node[MAX_LEVEL];
            Node<K, V> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            current = current.next[0];
            if (current != null && current.key.compareTo(key) == 0) {
                V previous = current.value;
                current.value = value;
                return previous;
            }

            int newLevel = randomLevel();
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    update[i] = head;
                }
                level = newLevel;
            }

            Node<K, V> inserted = new Node<K, V>(key, value, newLevel);
            for (int i = 0; i < newLevel; i++) {
                inserted.next[i] = update[i].next[i];
                update[i].next[i] = inserted;
            }
            size++;
            return null;
        }
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }

        synchronized (this) {
            Node<K, V> current = findPredecessor(key).next[0];
            return current != null && current.key.compareTo(key) == 0
                    ? current.value
                    : null;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }

        synchronized (this) {
            Node<K, V> current = findPredecessor(key).next[0];
            return current != null && current.key.compareTo(key) == 0;
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }

        synchronized (this) {
            Node<K, V>[] update = new Node[MAX_LEVEL];
            Node<K, V> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            current = current.next[0];
            if (current == null || current.key.compareTo(key) != 0) {
                return null;
            }

            for (int i = 0; i < level; i++) {
                if (update[i].next[i] == current) {
                    update[i].next[i] = current.next[i];
                }
            }

            while (level > 1 && head.next[level - 1] == null) {
                level--;
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
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    private Node<K, V> findPredecessor(K key) {
        Node<K, V> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }
        return current;
    }

    private int randomLevel() {
        int result = 1;
        while (result < MAX_LEVEL) {
            randomState ^= randomState << 13;
            randomState ^= randomState >>> 7;
            randomState ^= randomState << 17;
            if ((randomState & 1L) != 0) {
                break;
            }
            result++;
        }
        return result;
    }
}
