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
    private int currentLevel = 1;
    private int size;
    private int randomState = 0x6D2B79F5;

    private void requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] newNodeArray() {
        return (Node<K, V>[]) new Node[MAX_LEVEL];
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> current = head;
        for (int level = currentLevel - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].key.compareTo(key) < 0) {
                current = current.next[level];
            }
        }

        current = current.next[0];
        return current != null && current.key.compareTo(key) == 0 ? current : null;
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL) {
            randomState ^= randomState << 13;
            randomState ^= randomState >>> 17;
            randomState ^= randomState << 5;
            if ((randomState & 1) != 0) {
                break;
            }
            level++;
        }
        return level;
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

            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            current = current.next[0];
            if (current != null && current.key.compareTo(key) == 0) {
                V previousValue = current.value;
                current.value = value;
                return previousValue;
            }

            int nodeLevel = randomLevel();
            if (nodeLevel > currentLevel) {
                for (int level = currentLevel; level < nodeLevel; level++) {
                    update[level] = head;
                }
                currentLevel = nodeLevel;
            }

            Node<K, V> node = new Node<K, V>(key, value, nodeLevel);
            for (int level = 0; level < nodeLevel; level++) {
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

            for (int level = currentLevel - 1; level >= 0; level--) {
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

            for (int level = 0; level < currentLevel; level++) {
                if (update[level].next[level] != current) {
                    break;
                }
                update[level].next[level] = current.next[level];
            }

            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
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
            currentLevel = 1;
            size = 0;
        }
    }
}
