/**
 * A thread-safe, naturally ordered map backed by a skip list.
 *
 * Keys must be non-null and maintain a stable, mutually comparable natural
 * ordering. Values may be null; use containsKey to distinguish a null value
 * from an absent key.
 *
 * Operations do not wait for entries or capacity. An insertion that would
 * exceed Integer.MAX_VALUE entries throws IllegalStateException.
 * firstKey, lastKey, floorKey, and ceilingKey return null when no key qualifies.
 */
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
            this.next = (Node<K, V>[]) new Node<?, ?>[level];
        }
    }

    private final Object lock = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int level = 1;
    private int size;
    private long randomState = 0x9e3779b97f4a7c15L;

    private void requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private int randomLevel() {
        long x = randomState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        randomState = x;

        int result = 1;
        while (result < MAX_LEVEL && (x & 1L) == 0) {
            result++;
            x >>>= 1;
        }
        return result;
    }

    private Node<K, V> find(K key) {
        Node<K, V> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }
        current = current.next[0];
        return current != null && current.key.compareTo(key) == 0
                ? current : null;
    }

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> node = find(key);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return find(key) != null;
        }
    }

    /**
     * Associates key with value and returns its previous value, or null if absent.
     */
    public V put(K key, V value) {
        requireKey(key);
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node<?, ?>[MAX_LEVEL];
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
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("map is full");
            }

            int newLevel = randomLevel();
            Node<K, V> node = new Node<K, V>(key, value, newLevel);
            for (int i = level; i < newLevel; i++) {
                update[i] = head;
            }
            for (int i = 0; i < newLevel; i++) {
                node.next[i] = update[i].next[i];
                update[i].next[i] = node;
            }
            if (newLevel > level) {
                level = newLevel;
            }
            size++;
            return null;
        }
    }

    /**
     * Removes key and returns its previous value, or null if absent.
     */
    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node<?, ?>[MAX_LEVEL];
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
            for (int i = 0; i < current.next.length; i++) {
                update[i].next[i] = current.next[i];
            }
            while (level > 1 && head.next[level - 1] == null) {
                level--;
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
            for (int i = 0; i < level; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    public K firstKey() {
        synchronized (lock) {
            return head.next[0] == null ? null : head.next[0].key;
        }
    }

    public K lastKey() {
        synchronized (lock) {
            Node<K, V> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null) {
                    current = current.next[i];
                }
            }
            return current == head ? null : current.key;
        }
    }

    public K floorKey(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) <= 0) {
                    current = current.next[i];
                }
            }
            return current == head ? null : current.key;
        }
    }

    public K ceilingKey(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
            }
            current = current.next[0];
            return current == null ? null : current.key;
        }
    }
}
