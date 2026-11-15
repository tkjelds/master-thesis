@SkipListSet.ThreadSafe
public class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int height) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[height];
        }
    }

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL);
    private int level = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;
    private int comparisonDepth;

    public SkipListSet() {
    }

    /*
     * Elements must be non-null and have a consistent natural ordering that
     * does not change while they are in the set. Comparison-equivalent values
     * represent the same set element.
     *
     * Operations do not wait for elements or capacity. An insertion at the
     * maximum representable size throws IllegalStateException. first, last,
     * floor, and ceiling return null when no matching element exists.
     */

    public boolean add(E value) {
        requireValue(value);
        synchronized (lock) {
            checkMutationAllowed();
            Node<E>[] predecessors = newNodeArray();
            Node<E> candidate = find(value, predecessors);

            if (candidate != null && compare(candidate.value, value) == 0) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Maximum set size reached");
            }

            int height = randomLevel();
            Node<E> node = new Node<E>(value, height);
            if (height > level) {
                for (int i = level; i < height; i++) {
                    predecessors[i] = head;
                }
                level = height;
            }

            for (int i = 0; i < height; i++) {
                node.next[i] = predecessors[i].next[i];
                predecessors[i].next[i] = node;
            }
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        requireValue(value);
        synchronized (lock) {
            checkMutationAllowed();
            Node<E>[] predecessors = newNodeArray();
            Node<E> candidate = find(value, predecessors);

            if (candidate == null || compare(candidate.value, value) != 0) {
                return false;
            }

            for (int i = 0; i < candidate.next.length; i++) {
                predecessors[i].next[i] = candidate.next[i];
            }
            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        requireValue(value);
        synchronized (lock) {
            Node<E> candidate = find(value, null);
            return candidate != null && compare(candidate.value, value) == 0;
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
            checkMutationAllowed();
            for (int i = 0; i < level; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    public E first() {
        synchronized (lock) {
            Node<E> node = head.next[0];
            return node == null ? null : node.value;
        }
    }

    public E last() {
        synchronized (lock) {
            Node<E> node = head;
            for (int i = level - 1; i >= 0; i--) {
                while (node.next[i] != null) {
                    node = node.next[i];
                }
            }
            return node == head ? null : node.value;
        }
    }

    public E ceiling(E value) {
        requireValue(value);
        synchronized (lock) {
            Node<E> candidate = find(value, null);
            return candidate == null ? null : candidate.value;
        }
    }

    public E floor(E value) {
        requireValue(value);
        synchronized (lock) {
            Node<E> node = head;
            for (int i = level - 1; i >= 0; i--) {
                while (node.next[i] != null
                        && compare(node.next[i].value, value) <= 0) {
                    node = node.next[i];
                }
            }
            return node == head ? null : node.value;
        }
    }

    /* Called only while holding lock. */
    private Node<E> find(E value, Node<E>[] predecessors) {
        Node<E> node = head;
        for (int i = level - 1; i >= 0; i--) {
            while (node.next[i] != null
                    && compare(node.next[i].value, value) < 0) {
                node = node.next[i];
            }
            if (predecessors != null) {
                predecessors[i] = node;
            }
        }
        return node.next[0];
    }

    private int compare(E left, E right) {
        comparisonDepth++;
        try {
            return left.compareTo(right);
        } finally {
            comparisonDepth--;
        }
    }

    /* Prevent reentrant comparisons from invalidating an ongoing traversal. */
    private void checkMutationAllowed() {
        if (comparisonDepth != 0) {
            throw new IllegalStateException(
                    "Mutation during an element comparison is not allowed");
        }
    }

    private void requireValue(E value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not supported");
        }
    }

    private int randomLevel() {
        long x = randomState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        randomState = x;

        int height = 1;
        while (height < MAX_LEVEL && (x & 1L) != 0) {
            height++;
            x >>>= 1;
        }
        return height;
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] newNodeArray() {
        return (Node<E>[]) new Node<?>[MAX_LEVEL];
    }
}
