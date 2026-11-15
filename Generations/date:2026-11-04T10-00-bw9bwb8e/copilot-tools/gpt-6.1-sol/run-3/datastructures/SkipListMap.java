/**
 * A thread-safe skip-list map from int keys to int values.
 *
 * Keys are ordered by signed integer order. Each public map operation is
 * atomic under a single intrinsic monitor. Operations do not wait for data
 * or capacity; an absent mapping is represented by null.
 *
 * Returned entries are immutable snapshots. Size is a long because the
 * integer key domain contains 2^32 distinct keys.
 */
@SkipListMap.ThreadSafe
public final class SkipListMap {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    public static final class Entry {
        public final int key;
        public final int value;

        private Entry(int key, int value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class Node {
        private Entry entry;
        private final Node[] next;

        private Node(Entry entry, int height) {
            this.entry = entry;
            this.next = new Node[height];
        }
    }

    // The head is permanent and also serves as the map's private monitor.
    private final Node head = new Node(null, MAX_LEVEL);
    private int levels = 1;
    private long size;
    private int randomState = 0x6D2B79F5;

    public SkipListMap() {
    }

    public long size() {
        synchronized (head) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (head) {
            return size == 0;
        }
    }

    public boolean containsKey(int key) {
        synchronized (head) {
            return findNode(key) != null;
        }
    }

    /** Returns the mapping, or null if the key is absent. */
    public Entry get(int key) {
        synchronized (head) {
            Node node = findNode(key);
            return node == null ? null : node.entry;
        }
    }

    /** Inserts or replaces a mapping, returning its previous entry or null. */
    public Entry put(int key, int value) {
        synchronized (head) {
            return insert(key, value, false);
        }
    }

    /**
     * Inserts only if absent. Returns the existing entry, or null if inserted.
     */
    public Entry putIfAbsent(int key, int value) {
        synchronized (head) {
            return insert(key, value, true);
        }
    }

    /** Removes a mapping, returning its previous entry or null. */
    public Entry remove(int key) {
        synchronized (head) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node current = head;

            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].entry.key < key) {
                    current = current.next[level];
                }
                predecessors[level] = current;
            }

            Node target = predecessors[0].next[0];
            if (target == null || target.entry.key != key) {
                return null;
            }

            for (int level = 0; level < target.next.length; level++) {
                predecessors[level].next[level] = target.next[level];
            }

            while (levels > 1 && head.next[levels - 1] == null) {
                levels--;
            }

            size--;
            return target.entry;
        }
    }

    /** Returns the least-keyed entry, or null if empty. */
    public Entry firstEntry() {
        synchronized (head) {
            Node first = head.next[0];
            return first == null ? null : first.entry;
        }
    }

    /** Returns the greatest-keyed entry, or null if empty. */
    public Entry lastEntry() {
        synchronized (head) {
            Node current = head;

            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null) {
                    current = current.next[level];
                }
            }

            return current == head ? null : current.entry;
        }
    }

    /** Returns the greatest entry with key <= key, or null if none exists. */
    public Entry floorEntry(int key) {
        synchronized (head) {
            Node current = head;

            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].entry.key <= key) {
                    current = current.next[level];
                }
            }

            return current == head ? null : current.entry;
        }
    }

    /** Returns the least entry with key >= key, or null if none exists. */
    public Entry ceilingEntry(int key) {
        synchronized (head) {
            Node current = head;

            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].entry.key < key) {
                    current = current.next[level];
                }
            }

            Node successor = current.next[0];
            return successor == null ? null : successor.entry;
        }
    }

    public void clear() {
        synchronized (head) {
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            levels = 1;
            size = 0;
        }
    }

    // Private helpers are called only while holding the head monitor.
    private Node findNode(int key) {
        Node current = head;

        for (int level = levels - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].entry.key < key) {
                current = current.next[level];
            }
        }

        Node candidate = current.next[0];
        return candidate != null && candidate.entry.key == key
                ? candidate : null;
    }

    private Entry insert(int key, int value, boolean onlyIfAbsent) {
        Node[] predecessors = new Node[MAX_LEVEL];
        Node current = head;

        for (int level = levels - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].entry.key < key) {
                current = current.next[level];
            }
            predecessors[level] = current;
        }

        Node existing = predecessors[0].next[0];
        if (existing != null && existing.entry.key == key) {
            Entry previous = existing.entry;
            if (!onlyIfAbsent) {
                existing.entry = new Entry(key, value);
            }
            return previous;
        }

        int height = randomHeight();
        Node added = new Node(new Entry(key, value), height);

        for (int level = levels; level < height; level++) {
            predecessors[level] = head;
        }

        // Finish allocations before changing any structural links.
        for (int level = 0; level < height; level++) {
            added.next[level] = predecessors[level].next[level];
            predecessors[level].next[level] = added;
        }

        if (height > levels) {
            levels = height;
        }
        size++;
        return null;
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
}
