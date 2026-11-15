/**
 * An unbounded, thread-safe map ordered by the natural ordering of its keys.
 *
 * Null keys and values are not supported. A null result from a lookup, removal,
 * insertion, or navigation operation means that no corresponding entry
 * existed. Operations never wait for an entry to appear or space to become
 * available, though they may wait to acquire the map's intrinsic lock.
 *
 * Key ordering must remain stable while a key is in the map. Key comparisons
 * must not reenter or modify this map. The map does not synchronize access to
 * the internals of keys or values supplied by callers.
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
        Node(K key, V value, int levels) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node<?, ?>[levels];
        }
    }

    private final Object lock = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int levels = 1;
    private long size;
    private long randomState = 0x9E3779B97F4A7C15L;

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL) {
            long x = randomState;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            randomState = x;
            if ((x & 1L) != 0) {
                break;
            }
            level++;
        }
        return level;
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> current = head;
        for (int i = levels - 1; i >= 0; i--) {
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

    public V put(K key, V value) {
        requireKey(key);
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node<?, ?>[MAX_LEVEL];
            Node<K, V> current = head;
            for (int i = levels - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            Node<K, V> existing = current.next[0];
            if (existing != null && existing.key.compareTo(key) == 0) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            int newLevels = randomLevel();
            Node<K, V> node = new Node<K, V>(key, value, newLevels);
            for (int i = levels; i < newLevels; i++) {
                update[i] = head;
            }
            for (int i = 0; i < newLevels; i++) {
                node.next[i] = update[i].next[i];
                update[i].next[i] = node;
            }
            if (newLevels > levels) {
                levels = newLevels;
            }
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node<?, ?>[MAX_LEVEL];
            Node<K, V> current = head;
            for (int i = levels - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }
            Node<K, V> node = current.next[0];
            if (node == null || node.key.compareTo(key) != 0) {
                return null;
            }
            for (int i = 0; i < node.next.length; i++) {
                update[i].next[i] = node.next[i];
            }
            while (levels > 1 && head.next[levels - 1] == null) {
                levels--;
            }
            size--;
            return node.value;
        }
    }

    public long size() {
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
            for (int i = 0; i < levels; i++) {
                head.next[i] = null;
            }
            levels = 1;
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
            for (int i = levels - 1; i >= 0; i--) {
                while (current.next[i] != null) {
                    current = current.next[i];
                }
            }
            return current == head ? null : current.key;
        }
    }

    private K bound(K key, boolean below, boolean inclusive) {
        Node<K, V> current = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (current.next[i] != null) {
                int comparison = current.next[i].key.compareTo(key);
                if (comparison < 0 || (below && inclusive && comparison == 0)) {
                    current = current.next[i];
                } else {
                    break;
                }
            }
        }
        if (below) {
            return current == head ? null : current.key;
        }
        current = current.next[0];
        if (!inclusive && current != null && current.key.compareTo(key) == 0) {
            current = current.next[0];
        }
        return current == null ? null : current.key;
    }

    public K lowerKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return bound(key, true, false);
        }
    }

    public K floorKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return bound(key, true, true);
        }
    }

    public K ceilingKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return bound(key, false, true);
        }
    }

    public K higherKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return bound(key, false, false);
        }
    }
}
