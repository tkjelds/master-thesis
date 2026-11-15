/**
 * A thread-safe set of int values.
 *
 * Operations acquire an intrinsic monitor but never wait for the set to
 * become empty or nonempty. There is no fixed capacity limit; adding a new
 * value allocates a node and may fail if memory is exhausted.
 *
 * Every operation is atomic with respect to the other public operations.
 */
@ConcurrentSet.ThreadSafe
public final class ConcurrentSet {
    @interface ThreadSafe {}

    private static final int BUCKET_COUNT = 1024;

    private static final class Monitor {
    }

    private static final class Node {
        final int value;
        Node next;

        Node(int value, Node next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Monitor monitor = new Monitor();
    private final Node[] buckets = new Node[BUCKET_COUNT];
    private long elementCount;

    public ConcurrentSet() {
    }

    /**
     * Adds value, returning true only if it was not already present.
     */
    public boolean add(int value) {
        synchronized (monitor) {
            int index = bucketIndex(value);

            for (Node node = buckets[index]; node != null; node = node.next) {
                if (node.value == value) {
                    return false;
                }
            }

            Node node = new Node(value, buckets[index]);
            buckets[index] = node;
            elementCount++;
            return true;
        }
    }

    /**
     * Removes value, returning true only if it was present.
     */
    public boolean remove(int value) {
        synchronized (monitor) {
            int index = bucketIndex(value);
            Node previous = null;
            Node current = buckets[index];

            while (current != null) {
                if (current.value == value) {
                    if (previous == null) {
                        buckets[index] = current.next;
                    } else {
                        previous.next = current.next;
                    }

                    elementCount--;
                    return true;
                }

                previous = current;
                current = current.next;
            }

            return false;
        }
    }

    public boolean contains(int value) {
        synchronized (monitor) {
            int index = bucketIndex(value);

            for (Node node = buckets[index]; node != null; node = node.next) {
                if (node.value == value) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Uses long because there are 2^32 possible distinct int values.
     */
    public long size() {
        synchronized (monitor) {
            return elementCount;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return elementCount == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            elementCount = 0;
        }
    }

    private static int bucketIndex(int value) {
        int hash = value;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        hash ^= hash >>> 16;
        return hash & (BUCKET_COUNT - 1);
    }
}
