/*
 * Policy for operations that cannot complete immediately:
 *
 * 1. Bounded vs. Unbounded Capacity:
 *    - By default, instances constructed without an explicit capacity bound are unbounded.
 *    - When a positive capacity limit is configured, insertion of a new key into a full map
 *      (size >= capacity) cannot complete immediately. The calling thread blocks using wait()
 *      inside a while-loop until space is freed by a removal or clear operation.
 *    - In-place value updates for keys already present in the map do not increase the size,
 *      and thus proceed immediately without blocking regardless of whether the map is full.
 *    - The non-blocking offer() method attempts insertion and returns false immediately
 *      if the map is currently full and the key is absent.
 *
 * 2. Empty Structure:
 *    - Non-blocking retrieval and query methods (get, containsKey, remove, pollFirst, pollLast,
 *      firstKey, lastKey, firstEntry, lastEntry) return null or false immediately when the
 *      structure is empty or the queried key is absent.
 *    - Blocking retrieval methods (takeFirst, takeLast, takeFirstEntry, takeLastEntry, awaitNonEmpty)
 *      block using wait() inside a while-loop while the map is empty, and resume once an element
 *      is inserted.
 *
 * 3. Synchronization, Memory Visibility, and Notification:
 *    - All public operations synchronize on the instance's intrinsic monitor via synchronized
 *      blocks within method bodies (no synchronized method modifiers are used).
 *    - All state mutations that reduce the element count call notifyAll() to wake threads waiting
 *      for available capacity.
 *    - All state mutations that insert an element call notifyAll() to wake threads waiting for
 *      a non-empty structure.
 *    - No busy-waiting or arbitrary delays are used.
 *    - All non-final fields are declared volatile to prevent unsafe publication and ensure
 *      safe visibility across threads.
 */

@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<? super K>, V> {

    @interface ThreadSafe {}

    public interface Comparator<T> {
        int compare(T o1, T o2);
    }

    public static final class Entry<K, V> {
        private final K key;
        private final V value;

        public Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Entry<?, ?>)) {
                return false;
            }
            Entry<?, ?> other = (Entry<?, ?>) obj;
            boolean keyEqual = (key == null ? other.key == null : key.equals(other.key));
            boolean valEqual = (value == null ? other.value == null : value.equals(other.value));
            return keyEqual && valEqual;
        }

        @Override
        public int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }
    }

    private static final class Node<K, V> {
        private final K key;
        private volatile V value;
        private final Node<K, V>[] forward;

        @SuppressWarnings("unchecked")
        private Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.forward = (Node<K, V>[]) new Node<?, ?>[level];
        }
    }

    private static final int DEFAULT_MAX_LEVEL = 32;

    private final int maxLevel;
    private final int capacity;
    private final Comparator<? super K> comparator;
    private final Node<K, V> head;

    private volatile int currentLevel;
    private volatile int size;
    private volatile long randomState;

    public SkipListMap() {
        this(DEFAULT_MAX_LEVEL, Integer.MAX_VALUE, null);
    }

    public SkipListMap(int capacity) {
        this(DEFAULT_MAX_LEVEL, capacity, null);
    }

    public SkipListMap(Comparator<? super K> comparator) {
        this(DEFAULT_MAX_LEVEL, Integer.MAX_VALUE, comparator);
    }

    public SkipListMap(int capacity, Comparator<? super K> comparator) {
        this(DEFAULT_MAX_LEVEL, capacity, comparator);
    }

    public SkipListMap(int maxLevel, int capacity, Comparator<? super K> comparator) {
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel must be positive");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.maxLevel = maxLevel;
        this.capacity = capacity;
        this.comparator = comparator;
        this.head = new Node<K, V>(null, null, maxLevel);
        this.currentLevel = 1;
        this.size = 0;
        long seed = System.nanoTime() ^ 0x5DEECE66DL;
        this.randomState = (seed == 0L) ? 0x9E3779B97F4A7C15L : seed;
    }

    public V get(K key) {
        checkKey(key);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            return (node == null) ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        checkKey(key);
        synchronized (this) {
            return findNode(key) != null;
        }
    }

    public boolean containsValue(V value) {
        checkValue(value);
        synchronized (this) {
            Node<K, V> curr = head.forward[0];
            while (curr != null) {
                if (value.equals(curr.value)) {
                    return true;
                }
                curr = curr.forward[0];
            }
            return false;
        }
    }

    public V put(K key, V value) {
        checkKey(key);
        checkValue(value);
        synchronized (this) {
            while (capacity != Integer.MAX_VALUE && size >= capacity && findNode(key) == null) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted while waiting for capacity", e);
                }
            }
            return insertInternal(key, value);
        }
    }

    public V putBlocking(K key, V value) throws InterruptedException {
        checkKey(key);
        checkValue(value);
        synchronized (this) {
            while (capacity != Integer.MAX_VALUE && size >= capacity && findNode(key) == null) {
                this.wait();
            }
            return insertInternal(key, value);
        }
    }

    public boolean offer(K key, V value) {
        checkKey(key);
        checkValue(value);
        synchronized (this) {
            if (capacity != Integer.MAX_VALUE && size >= capacity && findNode(key) == null) {
                return false;
            }
            insertInternal(key, value);
            return true;
        }
    }

    public V putIfAbsent(K key, V value) {
        checkKey(key);
        checkValue(value);
        synchronized (this) {
            Node<K, V> existing = findNode(key);
            if (existing != null) {
                return existing.value;
            }
            while (capacity != Integer.MAX_VALUE && size >= capacity && findNode(key) == null) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted while waiting for capacity", e);
                }
            }
            return insertInternal(key, value);
        }
    }

    public V remove(K key) {
        checkKey(key);
        synchronized (this) {
            return removeInternal(key);
        }
    }

    public boolean remove(K key, V value) {
        checkKey(key);
        checkValue(value);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            if (node != null && value.equals(node.value)) {
                removeInternal(key);
                return true;
            }
            return false;
        }
    }

    public V replace(K key, V value) {
        checkKey(key);
        checkValue(value);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            if (node != null) {
                V oldVal = node.value;
                node.value = value;
                return oldVal;
            }
            return null;
        }
    }

    public boolean replace(K key, V oldValue, V newValue) {
        checkKey(key);
        checkValue(oldValue);
        checkValue(newValue);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            if (node != null && oldValue.equals(node.value)) {
                node.value = newValue;
                return true;
            }
            return false;
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

    public int capacity() {
        return capacity;
    }

    public boolean isBounded() {
        return capacity != Integer.MAX_VALUE;
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < maxLevel; i++) {
                head.forward[i] = null;
            }
            currentLevel = 1;
            size = 0;
            this.notifyAll();
        }
    }

    public K firstKey() {
        synchronized (this) {
            Node<K, V> first = head.forward[0];
            return (first == null) ? null : first.key;
        }
    }

    public K lastKey() {
        synchronized (this) {
            Node<K, V> last = findLastNode();
            return (last == null) ? null : last.key;
        }
    }

    public Entry<K, V> firstEntry() {
        synchronized (this) {
            Node<K, V> first = head.forward[0];
            return (first == null) ? null : new Entry<K, V>(first.key, first.value);
        }
    }

    public Entry<K, V> lastEntry() {
        synchronized (this) {
            Node<K, V> last = findLastNode();
            return (last == null) ? null : new Entry<K, V>(last.key, last.value);
        }
    }

    public Entry<K, V> pollFirstEntry() {
        synchronized (this) {
            Node<K, V> first = head.forward[0];
            if (first == null) {
                return null;
            }
            Entry<K, V> entry = new Entry<K, V>(first.key, first.value);
            removeInternal(first.key);
            return entry;
        }
    }

    public Entry<K, V> pollLastEntry() {
        synchronized (this) {
            Node<K, V> last = findLastNode();
            if (last == null) {
                return null;
            }
            Entry<K, V> entry = new Entry<K, V>(last.key, last.value);
            removeInternal(last.key);
            return entry;
        }
    }

    public V pollFirst() {
        synchronized (this) {
            Entry<K, V> entry = pollFirstEntry();
            return (entry == null) ? null : entry.getValue();
        }
    }

    public V pollLast() {
        synchronized (this) {
            Entry<K, V> entry = pollLastEntry();
            return (entry == null) ? null : entry.getValue();
        }
    }

    public Entry<K, V> takeFirstEntry() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                this.wait();
            }
            Node<K, V> first = head.forward[0];
            Entry<K, V> entry = new Entry<K, V>(first.key, first.value);
            removeInternal(first.key);
            return entry;
        }
    }

    public Entry<K, V> takeLastEntry() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                this.wait();
            }
            Node<K, V> last = findLastNode();
            Entry<K, V> entry = new Entry<K, V>(last.key, last.value);
            removeInternal(last.key);
            return entry;
        }
    }

    public V takeFirst() throws InterruptedException {
        synchronized (this) {
            return takeFirstEntry().getValue();
        }
    }

    public V takeLast() throws InterruptedException {
        synchronized (this) {
            return takeLastEntry().getValue();
        }
    }

    public void awaitNonEmpty() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                this.wait();
            }
        }
    }

    public void awaitAvailableCapacity() throws InterruptedException {
        synchronized (this) {
            while (capacity != Integer.MAX_VALUE && size >= capacity) {
                this.wait();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Entry<K, V>[] toArray() {
        synchronized (this) {
            Entry<K, V>[] result = (Entry<K, V>[]) new Entry<?, ?>[size];
            Node<K, V> curr = head.forward[0];
            int index = 0;
            while (curr != null && index < size) {
                result[index++] = new Entry<K, V>(curr.key, curr.value);
                curr = curr.forward[0];
            }
            return result;
        }
    }

    public Object[] keysToArray() {
        synchronized (this) {
            Object[] result = new Object[size];
            Node<K, V> curr = head.forward[0];
            int index = 0;
            while (curr != null && index < size) {
                result[index++] = curr.key;
                curr = curr.forward[0];
            }
            return result;
        }
    }

    public Object[] valuesToArray() {
        synchronized (this) {
            Object[] result = new Object[size];
            Node<K, V> curr = head.forward[0];
            int index = 0;
            while (curr != null && index < size) {
                result[index++] = curr.value;
                curr = curr.forward[0];
            }
            return result;
        }
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> curr = head;
        for (int i = currentLevel - 1; i >= 0; i--) {
            while (curr.forward[i] != null && compare(curr.forward[i].key, key) < 0) {
                curr = curr.forward[i];
            }
        }
        Node<K, V> candidate = curr.forward[0];
        if (candidate != null && compare(candidate.key, key) == 0) {
            return candidate;
        }
        return null;
    }

    private Node<K, V> findLastNode() {
        Node<K, V> curr = head;
        for (int i = currentLevel - 1; i >= 0; i--) {
            while (curr.forward[i] != null) {
                curr = curr.forward[i];
            }
        }
        return (curr == head) ? null : curr;
    }

    private V insertInternal(K key, V value) {
        @SuppressWarnings("unchecked")
        Node<K, V>[] update = (Node<K, V>[]) new Node<?, ?>[maxLevel];
        Node<K, V> curr = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (curr.forward[i] != null && compare(curr.forward[i].key, key) < 0) {
                curr = curr.forward[i];
            }
            update[i] = curr;
        }

        Node<K, V> candidate = curr.forward[0];
        if (candidate != null && compare(candidate.key, key) == 0) {
            V oldValue = candidate.value;
            candidate.value = value;
            return oldValue;
        }

        int level = randomLevel();
        if (level > currentLevel) {
            for (int i = currentLevel; i < level; i++) {
                update[i] = head;
            }
            currentLevel = level;
        }

        Node<K, V> newNode = new Node<K, V>(key, value, level);
        for (int i = 0; i < level; i++) {
            newNode.forward[i] = update[i].forward[i];
            update[i].forward[i] = newNode;
        }

        size++;
        this.notifyAll();
        return null;
    }

    private V removeInternal(K key) {
        @SuppressWarnings("unchecked")
        Node<K, V>[] update = (Node<K, V>[]) new Node<?, ?>[maxLevel];
        Node<K, V> curr = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (curr.forward[i] != null && compare(curr.forward[i].key, key) < 0) {
                curr = curr.forward[i];
            }
            update[i] = curr;
        }

        Node<K, V> target = curr.forward[0];
        if (target == null || compare(target.key, key) != 0) {
            return null;
        }

        for (int i = 0; i < target.forward.length; i++) {
            if (update[i].forward[i] == target) {
                update[i].forward[i] = target.forward[i];
            }
        }

        while (currentLevel > 1 && head.forward[currentLevel - 1] == null) {
            currentLevel--;
        }

        size--;
        this.notifyAll();
        return target.value;
    }

    private int randomLevel() {
        int level = 1;
        long state = randomState;
        while (level < maxLevel) {
            state ^= (state << 13);
            state ^= (state >>> 7);
            state ^= (state << 17);
            if ((state & 1L) == 0L) {
                break;
            }
            level++;
        }
        randomState = (state == 0L) ? 0x9E3779B97F4A7C15L : state;
        return level;
    }

    @SuppressWarnings("unchecked")
    private int compare(K k1, K k2) {
        if (comparator != null) {
            return comparator.compare(k1, k2);
        }
        return ((Comparable<? super K>) k1).compareTo(k2);
    }

    private static void checkKey(Object key) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
    }

    private static void checkValue(Object value) {
        if (value == null) {
            throw new NullPointerException("Value must not be null");
        }
    }
}

@skip_list_map.ThreadSafe
class skip_list_map<K extends Comparable<? super K>, V> extends SkipListMap<K, V> {
    @interface ThreadSafe {}

    public skip_list_map() {
        super();
    }

    public skip_list_map(int capacity) {
        super(capacity);
    }

    public skip_list_map(SkipListMap.Comparator<? super K> comparator) {
        super(comparator);
    }

    public skip_list_map(int capacity, SkipListMap.Comparator<? super K> comparator) {
        super(capacity, comparator);
    }

    public skip_list_map(int maxLevel, int capacity, SkipListMap.Comparator<? super K> comparator) {
        super(maxLevel, capacity, comparator);
    }
}
