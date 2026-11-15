<final_answer>
/Users/tore/master-thesis/pipeline/datastructures.py:1-24
/Users/tore/master-thesis/Generations/date:2026-10-14T10-32/copilot-no-tools/gpt-6-luna/datastructures/SkipListSet.java:1-166
/Users/tore/master-thesis/Generations/date:2026-10-14T10-32/copilot-no-tools/claude-haiku-5.5/datastructures/SkipListSet.java:1-244
/Users/tore/master-thesis/Generations/date:2026-09-21T20-02/copilot-no-tools/gpt-5.6-luna/skip_list_set.java:1-205
/Users/tore/master-thesis/Generations/date:2026-09-15T17-28/copilot-no-tools/gpt-5.6-luna/skip list set.java:1-260
</final_answer>
public final class SkipListSet<E extends Comparable<? super E>> {
    private static final int MAX_LEVEL = 32;
    private static final long DEFAULT_RANDOM_STATE = 0x9E3779B97F4A7C15L;

    private static final class Node {
        final Object value;
        final Node[] next;

        Node(Object value, int level) {
            this.value = value;
            this.next = new Node[level];
        }
    }

    private final Object lock = new Object();
    private final Node head = new Node(null, MAX_LEVEL);
    private int size;
    private long randomState;

    public SkipListSet() {
        long seed = System.nanoTime();
        randomState = seed == 0L ? DEFAULT_RANDOM_STATE : seed;
    }

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node[] update = new Node[MAX_LEVEL];
            findPath(value, update);

            Node candidate = update[0].next[0];
            if (candidate != null && compare(candidate.value, value) == 0) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("SkipListSet is full");
            }

            int level = randomLevel();
            Node node = new Node(value, level);
            for (int i = 0; i < level; i++) {
                node.next[i] = update[i].next[i];
                update[i].next[i] = node;
            }
            size++;
            return true;
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            return false;
        }

        synchronized (lock) {
            Node current = head;
            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && compare(current.next[level].value, value) < 0) {
                    current = current.next[level];
                }
            }

            Node candidate = current.next[0];
            return candidate != null && compare(candidate.value, value) == 0;
        }
    }

    public boolean remove(E value) {
        if (value == null) {
            return false;
        }

        synchronized (lock) {
            Node[] update = new Node[MAX_LEVEL];
            findPath(value, update);

            Node candidate = update[0].next[0];
            if (candidate == null || compare(candidate.value, value) != 0) {
                return false;
            }

            for (int i = 0; i < candidate.next.length; i++) {
                if (update[i].next[i] == candidate) {
                    update[i].next[i] = candidate.next[i];
                }
            }
            size--;
            return true;
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
            size = 0;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node current = head.next[0];
            int index = 0;
            while (current != null) {
                result[index++] = current.value;
                current = current.next[0];
            }
            return result;
        }
    }

    private void findPath(E value, Node[] update) {
        Node current = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && compare(current.next[level].value, value) < 0) {
                current = current.next[level];
            }
            update[level] = current;
        }
    }

    @SuppressWarnings("unchecked")
    private int compare(Object left, E right) {
        return ((E) left).compareTo(right);
    }

    private int randomLevel() {
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;

        int level = 1;
        while (level < MAX_LEVEL && (value & 1L) == 0L) {
            level++;
            value >>>= 1;
        }
        return level;
    }
}
