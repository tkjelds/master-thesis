@ConcurrentBoundedSet.ThreadSafe
public final class ConcurrentBoundedSet {
    @interface ThreadSafe {}


    private static final class Node {
        final Object key;
        final int hash;
        Node next;

        Node(Object key, int hash, Node next) {
            this.key = key;
            this.hash = hash;
            this.next = next;
        }
    }

    private static final int DEFAULT_INITIAL_BUCKETS = 16;
    private static final int MAX_BUCKETS = 1 << 30;

    private final Object lock = new Object();
    private final int maxSize;

    // All fields below are guarded by 'lock'.
    private Node[] buckets;
    private int size;

    /**
     * Creates a bounded, thread-safe set.
     *
     * Policy:
     *  - null elements are rejected with NullPointerException.
     *  - add(e) returns false if e is already present (never blocks in that case).
     *  - add(e) blocks (wait) while the set is full and e is absent, until
     *    space becomes available via remove() or clear().
     *  - remove(e) and contains(e) never block.
     *  - Blocked adders that are interrupted throw InterruptedException.
     */
    public ConcurrentBoundedSet(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.maxSize = maxSize;
        this.buckets = new Node[DEFAULT_INITIAL_BUCKETS];
        this.size = 0;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public boolean add(Object element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        final int h = hash(element);
        synchronized (lock) {
            while (true) {
                if (findNode(element, h) != null) {
                    return false;
                }
                if (size < maxSize) {
                    break;
                }
                // Set is full and element is absent: wait for space.
                lock.wait();
            }

            int idx = h & (buckets.length - 1);
            buckets[idx] = new Node(element, h, buckets[idx]);
            size++;

            if (size > buckets.length * 3L / 4 && buckets.length < MAX_BUCKETS) {
                resize(buckets.length << 1);
            }

            lock.notifyAll();
            return true;
        }
    }

    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }
        final int h = hash(element);
        synchronized (lock) {
            int idx = h & (buckets.length - 1);
            Node prev = null;
            Node cur = buckets[idx];
            while (cur != null) {
                if (cur.hash == h && cur.key.equals(element)) {
                    if (prev == null) {
                        buckets[idx] = cur.next;
                    } else {
                        prev.next = cur.next;
                    }
                    size--;
                    lock.notifyAll();
                    return true;
                }
                prev = cur;
                cur = cur.next;
            }
            return false;
        }
    }

    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }
        final int h = hash(element);
        synchronized (lock) {
            return findNode(element, h) != null;
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
            lock.notifyAll();
        }
    }

    // ---- Helpers: caller must hold 'lock' ----

    private Node findNode(Object element, int h) {
        int idx = h & (buckets.length - 1);
        Node cur = buckets[idx];
        while (cur != null) {
            if (cur.hash == h && cur.key.equals(element)) {
                return cur;
            }
            cur = cur.next;
        }
        return null;
    }

    private void resize(int newLength) {
        Node[] old = buckets;
        Node[] fresh = new Node[newLength];
        for (int i = 0; i < old.length; i++) {
            Node cur = old[i];
            while (cur != null) {
                int idx = cur.hash & (newLength - 1);
                fresh[idx] = new Node(cur.key, cur.hash, fresh[idx]);
                cur = cur.next;
            }
        }
        buckets = fresh;
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }
}
