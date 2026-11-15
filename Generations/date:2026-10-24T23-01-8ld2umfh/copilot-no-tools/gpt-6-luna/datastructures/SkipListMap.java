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
            this.next = (Node<K, V>[]) new Node[level + 1];
        }
    }

    private final Object lock = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL - 1);
    private int highestLevel;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> current = head;
            for (int level = highestLevel; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
            }
            current = current.next[0];
            return current != null && current.key.compareTo(key) == 0
                    ? current.value : null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> current = head;
            for (int level = highestLevel; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
            }
            current = current.next[0];
            return current != null && current.key.compareTo(key) == 0;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

            Node<K, V> current = head;
            for (int level = highestLevel; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            current = current.next[0];
            if (current != null && current.key.compareTo(key) == 0) {
                V previous = current.value;
                current.value = value;
                return previous;
            }

            int level = randomLevel();
            if (level > highestLevel) {
                for (int i = highestLevel + 1; i <= level; i++) {
                    update[i] = head;
                }
                highestLevel = level;
            }

            Node<K, V> inserted = new Node<K, V>(key, value, level);
            for (int i = 0; i <= level; i++) {
                inserted.next[i] = update[i].next[i];
                update[i].next[i] = inserted;
            }
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

            Node<K, V> current = head;
            for (int level = highestLevel; level >= 0; level--) {
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

            for (int level = 0; level <= highestLevel; level++) {
                if (update[level].next[level] != current) {
                    break;
                }
                update[level].next[level] = current.next[level];
            }

            while (highestLevel > 0 && head.next[highestLevel] == null) {
                highestLevel--;
            }
            size--;
            return current.value;
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
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            highestLevel = 0;
            size = 0;
        }
    }

    private int randomLevel() {
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;

        int level = 0;
        while (level < MAX_LEVEL - 1 && (value & 1L) == 0L) {
            level++;
            value >>>= 1;
        }
        return level;
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }
}
