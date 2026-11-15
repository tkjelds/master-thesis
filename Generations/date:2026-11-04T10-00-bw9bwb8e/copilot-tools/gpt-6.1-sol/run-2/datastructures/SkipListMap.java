/**
 * A concurrent skip-list map ordered by a caller-supplied comparator.
 *
 * All operations are serialized on a private intrinsic monitor. Operations
 * never wait for the map to become nonempty or for capacity to become available.
 * The map is unbounded, subject to available memory.
 *
 * The comparator must be nonnull, stable, side-effect-free, and define a total
 * ordering. It must not call back into this map. Keys must not change their
 * ordering while stored. Null keys are supported if the comparator supports
 * them. Null values are supported; containsKey distinguishes an absent key
 * from a key mapped to null.
 *
 * Comparator equality identifies keys. Replacing a value retains the original
 * stored key. Returned entries are immutable snapshots.
 */
@SkipListMap.ThreadSafe
public final class SkipListMap<K, V> {
    @interface ThreadSafe {}

    public interface Comparator<T> {
        int compare(T left, T right);
    }

    public static final class Entry<K, V> {
        public final K key;
        public final V value;

        private Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class Mapping<K, V> {
        final K key;
        V value;

        Mapping(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class Node<K, V> {
        final Mapping<K, V> mapping;
        final Node<K, V> down;
        Node<K, V> next;

        Node(Mapping<K, V> mapping, Node<K, V> down, Node<K, V> next) {
            this.mapping = mapping;
            this.down = down;
            this.next = next;
        }
    }

    private static final int MAX_LEVEL = 32;

    private final Comparator<? super K> comparator;
    private final Node<K, V> head;

    private long count;
    private int randomState = 0x6D2B79F5;

    public SkipListMap(Comparator<? super K> comparator) {
        this.comparator = comparator;

        Node<K, V> top = null;
        for (int level = 0; level < MAX_LEVEL; level++) {
            top = new Node<K, V>(null, top, null);
        }
        head = top;
    }

    public long size() {
        synchronized (head) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (head) {
            return count == 0;
        }
    }

    public boolean containsKey(K key) {
        synchronized (head) {
            return find(key) != null;
        }
    }

    public V get(K key) {
        synchronized (head) {
            Mapping<K, V> mapping = find(key);
            return mapping == null ? null : mapping.value;
        }
    }

    /**
     * Associates value with key and returns the previous value, or null if
     * absent. A null result can also mean the previous value was null.
     */
    public V put(K key, V value) {
        synchronized (head) {
            Mapping<K, V> existing = find(key);
            if (existing != null) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            Mapping<K, V> mapping = new Mapping<K, V>(key, value);
            insert(head, MAX_LEVEL, randomHeight(), mapping);
            count++;
            return null;
        }
    }

    /**
     * Removes key and returns its previous value, or null if absent.
     * A null result can also mean the removed value was null.
     */
    public V remove(K key) {
        synchronized (head) {
            Mapping<K, V> mapping = find(key);
            if (mapping == null) {
                return null;
            }

            Node<K, V> current = head;
            while (current != null) {
                while (current.next != null
                        && comparator.compare(current.next.mapping.key, key) < 0) {
                    current = current.next;
                }
                if (current.next != null && current.next.mapping == mapping) {
                    current.next = current.next.next;
                }
                current = current.down;
            }

            count--;
            return mapping.value;
        }
    }

    public void clear() {
        synchronized (head) {
            Node<K, V> current = head;
            while (current != null) {
                current.next = null;
                current = current.down;
            }
            count = 0;
        }
    }

    public Entry<K, V> firstEntry() {
        synchronized (head) {
            Node<K, V> current = head;
            while (current.down != null) {
                current = current.down;
            }
            return snapshot(current.next);
        }
    }

    public Entry<K, V> lastEntry() {
        synchronized (head) {
            Node<K, V> current = head;
            while (true) {
                while (current.next != null) {
                    current = current.next;
                }
                if (current.down == null) {
                    return snapshot(current);
                }
                current = current.down;
            }
        }
    }

    public Entry<K, V> lowerEntry(K key) {
        synchronized (head) {
            return snapshot(predecessor(key, false));
        }
    }

    public Entry<K, V> floorEntry(K key) {
        synchronized (head) {
            return snapshot(predecessor(key, true));
        }
    }

    public Entry<K, V> ceilingEntry(K key) {
        synchronized (head) {
            return snapshot(predecessor(key, false).next);
        }
    }

    public Entry<K, V> higherEntry(K key) {
        synchronized (head) {
            return snapshot(predecessor(key, true).next);
        }
    }

    private Mapping<K, V> find(K key) {
        Node<K, V> current = head;
        while (current != null) {
            while (current.next != null) {
                int comparison = comparator.compare(current.next.mapping.key, key);
                if (comparison == 0) {
                    return current.next.mapping;
                }
                if (comparison > 0) {
                    break;
                }
                current = current.next;
            }
            current = current.down;
        }
        return null;
    }

    private Node<K, V> predecessor(K key, boolean inclusive) {
        Node<K, V> current = head;
        while (true) {
            while (current.next != null) {
                int comparison = comparator.compare(current.next.mapping.key, key);
                if (comparison > 0 || (!inclusive && comparison == 0)) {
                    break;
                }
                current = current.next;
            }
            if (current.down == null) {
                return current;
            }
            current = current.down;
        }
    }

    private Node<K, V> insert(
            Node<K, V> start, int level, int height, Mapping<K, V> mapping) {
        Node<K, V> previous = start;
        while (previous.next != null
                && comparator.compare(previous.next.mapping.key, mapping.key) < 0) {
            previous = previous.next;
        }

        Node<K, V> lower = null;
        if (level > 1) {
            lower = insert(previous.down, level - 1, height, mapping);
        }

        if (level > height) {
            return lower;
        }

        Node<K, V> inserted = new Node<K, V>(mapping, lower, previous.next);
        previous.next = inserted;
        return inserted;
    }

    private int randomHeight() {
        int bits = randomState;
        bits ^= bits << 13;
        bits ^= bits >>> 17;
        bits ^= bits << 5;
        randomState = bits;

        int height = 1;
        while (height < MAX_LEVEL && (bits & 1) != 0) {
            height++;
            bits >>>= 1;
        }
        return height;
    }

    private Entry<K, V> snapshot(Node<K, V> node) {
        if (node == null || node.mapping == null) {
            return null;
        }
        return new Entry<K, V>(node.mapping.key, node.mapping.value);
    }
}
