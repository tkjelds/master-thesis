/**
 * A thread-safe skip-list map from long keys to long values.
 *
 * All operations are linearizable, using a private intrinsic monitor.
 * The map has no configured capacity. Empty lookups return null rather
 * than waiting. Operations may block while acquiring the monitor.
 *
 * Returned entries are immutable snapshots.
 */
@SkipListMap.ThreadSafe
public final class SkipListMap {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    public static final class Entry {
        public final long key;
        public final long value;

        private Entry(long key, long value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class Node {
        final long key;
        long value;
        final Node[] next;

        Node(long key, long value, int height) {
            this.key = key;
            this.value = value;
            this.next = new Node[height];
        }
    }

    // The sentinel is identified by identity, not by its key.
    private final Node head = new Node(0L, 0L, MAX_LEVEL);
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
            return size == 0L;
        }
    }

    public boolean containsKey(long key) {
        synchronized (head) {
            return find(key) != null;
        }
    }

    public Entry get(long key) {
        synchronized (head) {
            return snapshot(find(key));
        }
    }

    /** Inserts or replaces a value, returning the previous entry or null. */
    public Entry put(long key, long value) {
        synchronized (head) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node existing = findWithPredecessors(key, predecessors);
            if (existing != null) {
                Entry previous = snapshot(existing);
                existing.value = value;
                return previous;
            }

            int height = randomHeight();
            Node inserted = new Node(key, value, height);
            for (int i = levels; i < height; i++) {
                predecessors[i] = head;
            }
            for (int i = 0; i < height; i++) {
                inserted.next[i] = predecessors[i].next[i];
                predecessors[i].next[i] = inserted;
            }
            if (height > levels) {
                levels = height;
            }
            size++;
            return null;
        }
    }

    /** Removes a key, returning its previous entry or null. */
    public Entry remove(long key) {
        synchronized (head) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node removed = findWithPredecessors(key, predecessors);
            if (removed == null) {
                return null;
            }

            Entry previous = snapshot(removed);
            for (int i = 0; i < removed.next.length; i++) {
                predecessors[i].next[i] = removed.next[i];
            }
            while (levels > 1 && head.next[levels - 1] == null) {
                levels--;
            }
            size--;
            return previous;
        }
    }

    public void clear() {
        synchronized (head) {
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            levels = 1;
            size = 0L;
        }
    }

    public Entry firstEntry() {
        synchronized (head) {
            return snapshot(head.next[0]);
        }
    }

    public Entry lastEntry() {
        synchronized (head) {
            Node current = head;
            for (int i = levels - 1; i >= 0; i--) {
                while (current.next[i] != null) {
                    current = current.next[i];
                }
            }
            return current == head ? null : snapshot(current);
        }
    }

    /** Returns the greatest entry with a key strictly less than key. */
    public Entry lowerEntry(long key) {
        synchronized (head) {
            Node previous = predecessor(key);
            return previous == head ? null : snapshot(previous);
        }
    }

    /** Returns the greatest entry with a key less than or equal to key. */
    public Entry floorEntry(long key) {
        synchronized (head) {
            Node previous = predecessor(key);
            Node candidate = previous.next[0];
            if (candidate != null && candidate.key == key) {
                return snapshot(candidate);
            }
            return previous == head ? null : snapshot(previous);
        }
    }

    /** Returns the least entry with a key greater than or equal to key. */
    public Entry ceilingEntry(long key) {
        synchronized (head) {
            return snapshot(predecessor(key).next[0]);
        }
    }

    /** Returns the least entry with a key strictly greater than key. */
    public Entry higherEntry(long key) {
        synchronized (head) {
            Node candidate = predecessor(key).next[0];
            if (candidate != null && candidate.key == key) {
                candidate = candidate.next[0];
            }
            return snapshot(candidate);
        }
    }

    // All helpers below require head's monitor.
    private Node predecessor(long key) {
        Node current = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
        }
        return current;
    }

    private Node find(long key) {
        Node candidate = predecessor(key).next[0];
        return candidate != null && candidate.key == key ? candidate : null;
    }

    private Node findWithPredecessors(long key, Node[] predecessors) {
        Node current = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
            predecessors[i] = current;
        }
        Node candidate = current.next[0];
        return candidate != null && candidate.key == key ? candidate : null;
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

    private Entry snapshot(Node node) {
        return node == null ? null : new Entry(node.key, node.value);
    }
}
