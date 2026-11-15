@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private long randomState = 0x9E3779B97F4A7C15L;
    private int size;

    private static final class Node<K, V> {
        private final K key;
        private V value;
        private final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        private Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[level];
        }
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
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

            Node<K, V> current = head;
            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<K, V> existing = update[0].next[0];
            if (existing != null && existing.key.compareTo(key) == 0) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            Node<K, V> inserted = new Node<K, V>(key, value, randomLevel());
            for (int level = 0; level < inserted.next.length; level++) {
                inserted.next[level] = update[level].next[level];
                update[level].next[level] = inserted;
            }
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (this) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];

            Node<K, V> current = head;
            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<K, V> target = update[0].next[0];
            if (target == null || target.key.compareTo(key) != 0) {
                return null;
            }

            for (int level = 0; level < target.next.length; level++) {
                update[level].next[level] = target.next[level];
            }
            size--;
            return target.value;
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

        Node<K, V> candidate = current.next[0];
        return candidate != null && candidate.key.compareTo(key) == 0
                ? candidate : null;
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
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;
        return value;
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }
}
