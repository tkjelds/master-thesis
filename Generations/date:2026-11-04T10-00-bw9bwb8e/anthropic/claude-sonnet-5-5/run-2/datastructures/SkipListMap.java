@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int levels) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[levels];
        }
    }

    // Readers-writer lock built from an intrinsic monitor (writer preference).
    private final Object lock = new Object();
    private int activeReaders = 0;
    private int waitingWriters = 0;
    private boolean writerActive = false;

    // Guarded by the readers-writer lock.
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int level = 1;
    private int size = 0;
    private long seed = System.nanoTime() | 1L;

    public SkipListMap() {
    }

    // ---------- lock helpers ----------

    private void acquireRead() {
        boolean interrupted = false;
        synchronized (lock) {
            while (writerActive || waitingWriters > 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            activeReaders++;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseRead() {
        synchronized (lock) {
            activeReaders--;
            if (activeReaders == 0) {
                lock.notifyAll();
            }
        }
    }

    private void acquireWrite() {
        boolean interrupted = false;
        synchronized (lock) {
            waitingWriters++;
            try {
                while (writerActive || activeReaders > 0) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                waitingWriters--;
            }
            writerActive = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseWrite() {
        synchronized (lock) {
            writerActive = false;
            lock.notifyAll();
        }
    }

    // ---------- internal helpers (caller holds appropriate lock) ----------

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL) {
            long x = seed;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            seed = x;
            if ((x & 1L) == 1L) {
                lvl++;
            } else {
                break;
            }
        }
        return lvl;
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && x.next[i].key.compareTo(key) < 0) {
                x = x.next[i];
            }
        }
        Node<K, V> c = x.next[0];
        if (c != null && c.key.compareTo(key) == 0) {
            return c;
        }
        return null;
    }

    private V putInternal(K key, V value, boolean onlyIfAbsent) {
        @SuppressWarnings("unchecked")
        Node<K, V>[] preds = (Node<K, V>[]) new Node[MAX_LEVEL];
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && x.next[i].key.compareTo(key) < 0) {
                x = x.next[i];
            }
            preds[i] = x;
        }
        Node<K, V> c = x.next[0];
        if (c != null && c.key.compareTo(key) == 0) {
            V old = c.value;
            if (!onlyIfAbsent) {
                c.value = value;
            }
            return old;
        }
        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) {
                preds[i] = head;
            }
            level = lvl;
        }
        Node<K, V> n = new Node<K, V>(key, value, lvl);
        for (int i = 0; i < lvl; i++) {
            n.next[i] = preds[i].next[i];
            preds[i].next[i] = n;
        }
        size++;
        return null;
    }

    // ---------- public API ----------

    /** Associates value with key; returns previous value or null. */
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        acquireWrite();
        try {
            return putInternal(key, value, false);
        } finally {
            releaseWrite();
        }
    }

    /** Inserts only if absent; returns existing value or null if inserted. */
    public V putIfAbsent(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        acquireWrite();
        try {
            return putInternal(key, value, true);
        } finally {
            releaseWrite();
        }
    }

    /** Returns the value for key, or null if absent. */
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        acquireRead();
        try {
            Node<K, V> n = findNode(key);
            return n == null ? null : n.value;
        } finally {
            releaseRead();
        }
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /** Removes key; returns removed value or null if absent. */
    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        acquireWrite();
        try {
            @SuppressWarnings("unchecked")
            Node<K, V>[] preds = (Node<K, V>[]) new Node[MAX_LEVEL];
            Node<K, V> x = head;
            for (int i = level - 1; i >= 0; i--) {
                while (x.next[i] != null && x.next[i].key.compareTo(key) < 0) {
                    x = x.next[i];
                }
                preds[i] = x;
            }
            Node<K, V> target = x.next[0];
            if (target == null || target.key.compareTo(key) != 0) {
                return null;
            }
            for (int i = 0; i < level; i++) {
                if (preds[i].next[i] == target) {
                    preds[i].next[i] = target.next[i];
                }
            }
            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
            size--;
            return target.value;
        } finally {
            releaseWrite();
        }
    }

    /** Replaces value only if key is present; returns old value or null. */
    public V replace(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        acquireWrite();
        try {
            Node<K, V> n = findNode(key);
            if (n == null) {
                return null;
            }
            V old = n.value;
            n.value = value;
            return old;
        } finally {
            releaseWrite();
        }
    }

    public int size() {
        acquireRead();
        try {
            return size;
        } finally {
            releaseRead();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public void clear() {
        acquireWrite();
        try {
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        } finally {
            releaseWrite();
        }
    }

    /** Smallest key, or null if empty. */
    public K firstKey() {
        acquireRead();
        try {
            Node<K, V> n = head.next[0];
            return n == null ? null : n.key;
        } finally {
            releaseRead();
        }
    }

    /** Largest key, or null if empty. */
    public K lastKey() {
        acquireRead();
        try {
            Node<K, V> x = head;
            for (int i = level - 1; i >= 0; i--) {
                while (x.next[i] != null) {
                    x = x.next[i];
                }
            }
            return x == head ? null : x.key;
        } finally {
            releaseRead();
        }
    }

    /** Greatest key less than or equal to the given key, or null. */
    public K floorKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        acquireRead();
        try {
            Node<K, V> x = head;
            for (int i = level - 1; i >= 0; i--) {
                while (x.next[i] != null && x.next[i].key.compareTo(key) <= 0) {
                    x = x.next[i];
                }
            }
            return x == head ? null : x.key;
        } finally {
            releaseRead();
        }
    }

    /** Least key greater than or equal to the given key, or null. */
    public K ceilingKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        acquireRead();
        try {
            Node<K, V> x = head;
            for (int i = level - 1; i >= 0; i--) {
                while (x.next[i] != null && x.next[i].key.compareTo(key) < 0) {
                    x = x.next[i];
                }
            }
            Node<K, V> c = x.next[0];
            return c == null ? null : c.key;
        } finally {
            releaseRead();
        }
    }

    /** Removes and returns the smallest key's value, or null if empty. */
    public V pollFirstValue() {
        acquireWrite();
        try {
            Node<K, V> first = head.next[0];
            if (first == null) {
                return null;
            }
            for (int i = 0; i < level; i++) {
                if (head.next[i] == first) {
                    head.next[i] = first.next[i];
                }
            }
            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
            size--;
            return first.value;
        } finally {
            releaseWrite();
        }
    }
}
