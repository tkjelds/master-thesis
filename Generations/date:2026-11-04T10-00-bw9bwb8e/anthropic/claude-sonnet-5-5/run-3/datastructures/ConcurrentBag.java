@ConcurrentBag.ThreadSafe
public class ConcurrentBag<E> {
    @interface ThreadSafe {}


    private static final class Node {
        final Object key;
        final int hash;
        int count;
        Node next;

        Node(Object key, int hash, int count, Node next) {
            this.key = key;
            this.hash = hash;
            this.count = count;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final int capacity;

    // All fields below are guarded by 'lock'.
    private Node[] table;
    private int distinct;
    private int total;
    private int scanStart;

    public ConcurrentBag() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.table = new Node[16];
    }

    private static int spread(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private Node find(Object key, int hash) {
        Node n = table[hash & (table.length - 1)];
        while (n != null) {
            if (n.hash == hash && (n.key == key || n.key.equals(key))) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    private void insertLocked(Object key, int n) {
        int hash = spread(key);
        Node node = find(key, hash);
        if (node != null) {
            node.count += n;
        } else {
            if (distinct + 1 > table.length - (table.length >> 2)) {
                resizeLocked();
            }
            int idx = hash & (table.length - 1);
            table[idx] = new Node(key, hash, n, table[idx]);
            distinct++;
        }
        total += n;
    }

    private void resizeLocked() {
        Node[] old = table;
        Node[] nt = new Node[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Node n = old[i];
            while (n != null) {
                Node next = n.next;
                int idx = n.hash & (nt.length - 1);
                n.next = nt[idx];
                nt[idx] = n;
                n = next;
            }
        }
        table = nt;
    }

    // Removes one copy of the key; returns true if present.
    private boolean removeOneLocked(Object key) {
        int hash = spread(key);
        int idx = hash & (table.length - 1);
        Node prev = null;
        Node n = table[idx];
        while (n != null) {
            if (n.hash == hash && (n.key == key || n.key.equals(key))) {
                n.count--;
                total--;
                if (n.count == 0) {
                    if (prev == null) {
                        table[idx] = n.next;
                    } else {
                        prev.next = n.next;
                    }
                    distinct--;
                }
                return true;
            }
            prev = n;
            n = n.next;
        }
        return false;
    }

    // Precondition: total > 0.
    private Object removeAnyLocked() {
        int len = table.length;
        for (int i = 0; i < len; i++) {
            int idx = (scanStart + i) & (len - 1);
            Node n = table[idx];
            if (n != null) {
                Object key = n.key;
                removeOneLocked(key);
                scanStart = idx;
                return key;
            }
        }
        throw new IllegalStateException("inconsistent bag state");
    }

    /** Adds one element; blocks while the bag is full. */
    public void add(E element) throws InterruptedException {
        addCopies(element, 1);
    }

    /** Adds n copies atomically; blocks until there is room for all n. */
    public void addCopies(E element, int n) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
        }
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        if (n > capacity) {
            throw new IllegalArgumentException("n exceeds capacity");
        }
        synchronized (lock) {
            while (capacity - total < n) {
                lock.wait();
            }
            insertLocked(element, n);
            lock.notifyAll();
        }
    }

    /** Adds one element if there is room; returns false if full. */
    public boolean tryAdd(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        synchronized (lock) {
            if (total >= capacity) {
                return false;
            }
            insertLocked(element, 1);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns an arbitrary element; blocks while the bag is empty. */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (total == 0) {
                lock.wait();
            }
            Object o = removeAnyLocked();
            lock.notifyAll();
            return (E) o;
        }
    }

    /** Removes and returns an arbitrary element, or null if the bag is empty. */
    @SuppressWarnings("unchecked")
    public E tryTake() {
        synchronized (lock) {
            if (total == 0) {
                return null;
            }
            Object o = removeAnyLocked();
            lock.notifyAll();
            return (E) o;
        }
    }

    /** Removes one copy of the element, blocking until one is present. */
    public void takeSpecific(Object element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
        }
        synchronized (lock) {
            while (!removeOneLocked(element)) {
                lock.wait();
            }
            lock.notifyAll();
        }
    }

    /** Removes one copy of the element if present; non-blocking. */
    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            boolean removed = removeOneLocked(element);
            if (removed) {
                lock.notifyAll();
            }
            return removed;
        }
    }

    /** Number of copies of the element in the bag. */
    public int count(Object element) {
        if (element == null) {
            return 0;
        }
        synchronized (lock) {
            Node n = find(element, spread(element));
            return n == null ? 0 : n.count;
        }
    }

    public boolean contains(Object element) {
        return count(element) > 0;
    }

    public int size() {
        synchronized (lock) {
            return total;
        }
    }

    public int distinctSize() {
        synchronized (lock) {
            return distinct;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return total == 0;
        }
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (lock) {
            table = new Node[16];
            distinct = 0;
            total = 0;
            scanStart = 0;
            lock.notifyAll();
        }
    }

    /** Returns a consistent snapshot of all elements (with repetitions). */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[total];
            int k = 0;
            for (int i = 0; i < table.length; i++) {
                for (Node n = table[i]; n != null; n = n.next) {
                    for (int c = 0; c < n.count; c++) {
                        result[k++] = n.key;
                    }
                }
            }
            return result;
        }
    }
}
