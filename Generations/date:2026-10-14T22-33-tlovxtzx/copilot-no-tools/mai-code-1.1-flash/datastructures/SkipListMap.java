@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    /*
     * Blocking policy:
     * - If maxEntries > 0, put/putIfAbsent block while full and the key is absent;
     *   waiting threads are released by any successful removal or clear.
     * - takeFirst() blocks while the map is empty; pollFirst() returns null if empty.
     * - All public methods synchronize on the same monitor to maintain a single,
     *   consistent skip-list state and avoid visibility/race issues.
     */
    private final Object lock = new Object();
    private final int maxLevel;
    private final int maxEntries;
    private final Node<K, V> head;
    private int size;
    private long randomState;

    public SkipListMap() {
        this(16, -1);
    }

    public SkipListMap(int maxLevel) {
        this(maxLevel, -1);
    }

    public SkipListMap(int maxLevel, int maxEntries) {
        if (maxLevel <= 0) {
            throw new IllegalArgumentException("maxLevel must be positive");
        }
        this.maxLevel = maxLevel;
        this.maxEntries = maxEntries;
        this.head = new Node<K, V>(null, null, maxLevel);
        this.randomState = System.nanoTime() ^ System.identityHashCode(this);
    }

    private static final class Node<K, V> {
        final K key;
        V value;
        @SuppressWarnings("unchecked")
        Node<K, V>[] forward = (Node<K, V>[]) new Node[0];

        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.forward = (Node<K, V>[]) new Node[level + 1];
        }
    }

    private int compare(K left, K right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private Node<K, V>[] findPredecessorsLocked(K key) {
        @SuppressWarnings("unchecked")
        Node<K, V>[] update = (Node<K, V>[]) new Node[maxLevel + 1];
        Node<K, V> current = head;
        for (int level = maxLevel; level >= 0; level--) {
            while (current.forward[level] != null && compare(current.forward[level].key, key) < 0) {
                current = current.forward[level];
            }
            update[level] = current;
        }
        return update;
    }

    private Node<K, V> findNodeLocked(K key) {
        Node<K, V> current = head;
        for (int level = maxLevel; level >= 0; level--) {
            while (current.forward[level] != null && compare(current.forward[level].key, key) < 0) {
                current = current.forward[level];
            }
        }
        Node<K, V> candidate = current.forward[0];
        if (candidate != null && compare(candidate.key, key) == 0) {
            return candidate;
        }
        return null;
    }

    private int randomLevel() {
        int level = 0;
        long state = randomState;
        while (level < maxLevel && ((state = state * 6364136223846793005L + 1442695040888963407L) & 1L) == 0L) {
            level++;
        }
        randomState = state;
        return level;
    }

    private void waitForCapacityLocked(K key) {
        while (maxEntries > 0 && size >= maxEntries && findNodeLocked(key) == null) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for capacity", e);
            }
        }
    }

    private V deleteNodeLocked(Node<K, V> node) {
        Node<K, V>[] update = findPredecessorsLocked(node.key);
        for (int i = node.forward.length - 1; i >= 0; i--) {
            if (update[i].forward[i] == node) {
                update[i].forward[i] = node.forward[i];
            }
        }
        size--;
        lock.notifyAll();
        return node.value;
    }

    public V put(K key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        synchronized (lock) {
            waitForCapacityLocked(key);

            Node<K, V>[] update = findPredecessorsLocked(key);
            Node<K, V> existing = update[0].forward[0];
            if (existing != null && compare(existing.key, key) == 0) {
                V oldValue = existing.value;
                existing.value = value;
                return oldValue;
            }

            int level = randomLevel();
            Node<K, V> node = new Node<K, V>(key, value, level);
            for (int i = 0; i <= level; i++) {
                node.forward[i] = update[i].forward[i];
                update[i].forward[i] = node;
            }
            size++;
            lock.notifyAll();
            return null;
        }
    }

    public void putValue(K key, V value) {
        put(key, value);
    }

    public V set(K key, V value) {
        return put(key, value);
    }

    public V replace(K key, V value) {
        return put(key, value);
    }

    public V putIfAbsent(K key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        synchronized (lock) {
            waitForCapacityLocked(key);

            Node<K, V> existing = findNodeLocked(key);
            if (existing != null) {
                return existing.value;
            }

            Node<K, V>[] update = findPredecessorsLocked(key);
            int level = randomLevel();
            Node<K, V> node = new Node<K, V>(key, value, level);
            for (int i = 0; i <= level; i++) {
                node.forward[i] = update[i].forward[i];
                update[i].forward[i] = node;
            }
            size++;
            lock.notifyAll();
            return null;
        }
    }

    public V get(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        synchronized (lock) {
            Node<K, V> node = findNodeLocked(key);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        synchronized (lock) {
            return findNodeLocked(key) != null;
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        synchronized (lock) {
            Node<K, V> node = findNodeLocked(key);
            if (node == null) {
                return null;
            }
            V oldValue = deleteNodeLocked(node);
            return oldValue;
        }
    }

    public V removeValue(K key) {
        return remove(key);
    }

    public V pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> first = head.forward[0];
            return deleteNodeLocked(first);
        }
    }

    public V takeFirst() {
        synchronized (lock) {
            while (size == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for an element", e);
                }
            }
            Node<K, V> first = head.forward[0];
            return deleteNodeLocked(first);
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return maxEntries;
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i <= maxLevel; i++) {
                head.forward[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }
}

@skip_list_map.ThreadSafe
class skip_list_map<K extends Comparable<? super K>, V> extends SkipListMap<K, V> {
    @interface ThreadSafe {}

    public skip_list_map() {
        super();
    }

    public skip_list_map(int maxLevel) {
        super(maxLevel);
    }

    public skip_list_map(int maxLevel, int maxEntries) {
        super(maxLevel, maxEntries);
    }
}
