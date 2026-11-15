@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node {
        final Object key;
        Object value;
        final Node[] next;

        Node(Object key, Object value, int level) {
            this.key = key;
            this.value = value;
            this.next = new Node[level];
        }
    }

    private final Object lock = new Object();
    private final Node head = new Node(null, null, MAX_LEVEL);
    private int level = 1;
    private int size;
    private int randomState = 0x6D2B79F5;

    public SkipListMap() {
    }

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node current = head;
            for (int i = level - 1; i >= 0; i--) {
                Node next;
                while ((next = current.next[i]) != null
                        && compareKeys(next.key, key) < 0) {
                    current = next;
                }
            }

            Node found = current.next[0];
            return found != null && compareKeys(found.key, key) == 0
                    ? valueOf(found.value)
                    : null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            Node current = head;
            for (int i = level - 1; i >= 0; i--) {
                Node next;
                while ((next = current.next[i]) != null
                        && compareKeys(next.key, key) < 0) {
                    current = next;
                }
            }

            Node found = current.next[0];
            return found != null && compareKeys(found.key, key) == 0;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        synchronized (lock) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node current = head;

            for (int i = level - 1; i >= 0; i--) {
                Node next;
                while ((next = current.next[i]) != null
                        && compareKeys(next.key, key) < 0) {
                    current = next;
                }
                predecessors[i] = current;
            }

            Node candidate = predecessors[0].next[0];
            if (candidate != null && compareKeys(candidate.key, key) == 0) {
                V previous = valueOf(candidate.value);
                candidate.value = value;
                return previous;
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("map size limit reached");
            }

            int newLevel = randomLevel();
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    predecessors[i] = head;
                }
                level = newLevel;
            }

            Node inserted = new Node(key, value, newLevel);
            for (int i = 0; i < newLevel; i++) {
                inserted.next[i] = predecessors[i].next[i];
                predecessors[i].next[i] = inserted;
            }
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node current = head;

            for (int i = level - 1; i >= 0; i--) {
                Node next;
                while ((next = current.next[i]) != null
                        && compareKeys(next.key, key) < 0) {
                    current = next;
                }
                predecessors[i] = current;
            }

            Node candidate = predecessors[0].next[0];
            if (candidate == null || compareKeys(candidate.key, key) != 0) {
                return null;
            }

            for (int i = 0; i < level; i++) {
                if (predecessors[i].next[i] == candidate) {
                    predecessors[i].next[i] = candidate.next[i];
                }
            }

            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }

            size--;
            return valueOf(candidate.value);
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
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    private int randomLevel() {
        int result = 1;
        while (result < MAX_LEVEL && (nextRandomInt() & 1) == 0) {
            result++;
        }
        return result;
    }

    private int nextRandomInt() {
        int value = randomState;
        value ^= value << 13;
        value ^= value >>> 17;
        value ^= value << 5;
        randomState = value;
        return value;
    }

    private static int compareKeys(Object left, Object right) {
        @SuppressWarnings("unchecked")
        Comparable<Object> comparable = (Comparable<Object>) left;
        return comparable.compareTo(right);
    }

    @SuppressWarnings("unchecked")
    private V valueOf(Object value) {
        return (V) value;
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }
}
