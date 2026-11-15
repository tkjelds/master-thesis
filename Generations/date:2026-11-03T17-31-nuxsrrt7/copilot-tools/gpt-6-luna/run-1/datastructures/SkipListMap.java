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
    private int level = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public V get(K key) {
        requireKey(key);
        synchronized (mutex) {
            Node<K, V> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
            }
            current = current.next[0];
            return current != null && current.key.compareTo(key) == 0
                    ? current.value : null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (mutex) {
            Node<K, V> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
            }
            current = current.next[0];
            return current != null && current.key.compareTo(key) == 0;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        synchronized (mutex) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

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

    public V remove(K key) {
        requireKey(key);
        synchronized (mutex) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

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
                if (update[i].next[i] != current) {
                    break;
                }
                update[i].next[i] = current.next[i];
            }

            size--;
            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
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
            for (int i = 0; i < level; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    private int randomLevel() {
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;

        int result = 1;
        while ((value & 1L) != 0L && result < MAX_LEVEL) {
            result++;
            value >>>= 1;
        }
        return result;
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }
}
