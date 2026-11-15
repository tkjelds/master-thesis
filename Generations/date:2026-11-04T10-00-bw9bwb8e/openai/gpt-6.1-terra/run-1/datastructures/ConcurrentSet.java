/**
 * A thread-safe, unbounded-by-policy hash set.
 *
 * Null elements are permitted. Elements must have stable hashCode and equals
 * behavior while stored, and those methods must not modify this set.
 *
 * Operations do not wait for elements or capacity, although they may wait to
 * acquire the set's monitor. Adding a new element at Integer.MAX_VALUE size
 * throws IllegalStateException.
 */
@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private static final class Node {
        final Object value;
        final int hash;
        Node next;

        Node(Object value, int hash, Node next) {
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node[] buckets = new Node[INITIAL_CAPACITY];
    private int size;
    private int threshold = INITIAL_CAPACITY - INITIAL_CAPACITY / 4;

    private static int hash(Object value) {
        int h = value == null ? 0 : value.hashCode();
        return h ^ (h >>> 16);
    }

    private static boolean matches(Object value, Node node, int hash) {
        return node.hash == hash
                && (value == node.value
                    || (value != null && value.equals(node.value)));
    }

    /**
     * Adds an element if it is not already present.
     *
     * @return true if the set changed
     */
    public boolean add(E value) {
        synchronized (lock) {
            int hash = hash(value);
            int index = hash & (buckets.length - 1);

            for (Node node = buckets[index]; node != null; node = node.next) {
                if (matches(value, node, hash)) {
                    return false;
                }
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Set has reached its maximum size");
            }

            if (size >= threshold && buckets.length < MAX_CAPACITY) {
                grow();
                index = hash & (buckets.length - 1);
            }

            buckets[index] = new Node(value, hash, buckets[index]);
            size++;
            return true;
        }
    }

    /**
     * Returns whether an equal element is present.
     */
    public boolean contains(Object value) {
        synchronized (lock) {
            int hash = hash(value);
            int index = hash & (buckets.length - 1);

            for (Node node = buckets[index]; node != null; node = node.next) {
                if (matches(value, node, hash)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Removes an equal element if present.
     *
     * @return true if the set changed
     */
    public boolean remove(Object value) {
        synchronized (lock) {
            int hash = hash(value);
            int index = hash & (buckets.length - 1);
            Node previous = null;
            Node node = buckets[index];

            while (node != null) {
                if (matches(value, node, hash)) {
                    if (previous == null) {
                        buckets[index] = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return true;
                }
                previous = node;
                node = node.next;
            }
            return false;
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
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            size = 0;
        }
    }

    // Called only while holding lock.
    private void grow() {
        int newCapacity = buckets.length << 1;
        Node[] newBuckets = new Node[newCapacity];

        for (int i = 0; i < buckets.length; i++) {
            Node node = buckets[i];
            while (node != null) {
                Node next = node.next;
                int index = node.hash & (newCapacity - 1);
                node.next = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        threshold = newCapacity == MAX_CAPACITY
                ? Integer.MAX_VALUE
                : newCapacity - newCapacity / 4;
    }
}
