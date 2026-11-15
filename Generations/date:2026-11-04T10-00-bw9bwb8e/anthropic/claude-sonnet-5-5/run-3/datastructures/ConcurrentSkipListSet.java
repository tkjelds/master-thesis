import java.lang.Comparable;

@ConcurrentSkipListSet.ThreadSafe
public class ConcurrentSkipListSet<T extends Comparable<T>> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int levels) {
            this.value = value;
            this.next = (Node<T>[]) new Node[levels];
        }
    }

    // Readers-writer coordination, guarded by stateLock.
    private final Object stateLock = new Object();
    private int activeReaders = 0;
    private boolean writerActive = false;
    private int waitingWriters = 0;

    // Skip list data: only accessed while holding read or write access.
    private final Node<T> head = new Node<T>(null, MAX_LEVEL);
    private int currentLevel = 1;
    private int count = 0;
    private long rngState = 0x2545F4914F6CDD1DL;

    public ConcurrentSkipListSet() {
    }

    // ---------- lock helpers (built only on monitors, wait/notifyAll) ----------

    private void acquireRead() {
        boolean interrupted = false;
        synchronized (stateLock) {
            while (writerActive || waitingWriters > 0) {
                try {
                    stateLock.wait();
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
        synchronized (stateLock) {
            activeReaders--;
            if (activeReaders == 0) {
                stateLock.notifyAll();
            }
        }
    }

    private void acquireWrite() {
        boolean interrupted = false;
        synchronized (stateLock) {
            waitingWriters++;
            while (writerActive || activeReaders > 0) {
                try {
                    stateLock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            waitingWriters--;
            writerActive = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseWrite() {
        synchronized (stateLock) {
            writerActive = false;
            stateLock.notifyAll();
        }
    }

    // ---------- internal helpers (caller holds appropriate access) ----------

    private int randomLevel() {
        // xorshift64*; only called under exclusive write access
        long x = rngState;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rngState = x;
        long r = x * 0x2545F4914F6CDD1DL;
        int level = 1;
        while (level < MAX_LEVEL && (r & 1L) == 1L) {
            level++;
            r >>>= 1;
        }
        return level;
    }

    private Node<T> findGreaterOrEqual(T value, Node<T>[] preds) {
        Node<T> pred = head;
        for (int lvl = currentLevel - 1; lvl >= 0; lvl--) {
            Node<T> cur = pred.next[lvl];
            while (cur != null && cur.value.compareTo(value) < 0) {
                pred = cur;
                cur = pred.next[lvl];
            }
            if (preds != null) {
                preds[lvl] = pred;
            }
        }
        return pred.next[0];
    }

    // ---------- public operations ----------

    /** Adds value; returns true if it was not already present. */
    @SuppressWarnings("unchecked")
    public boolean add(T value) {
        if (value == null) {
            throw new NullPointerException();
        }
        acquireWrite();
        try {
            Node<T>[] preds = (Node<T>[]) new Node[MAX_LEVEL];
            Node<T> found = findGreaterOrEqual(value, preds);
            if (found != null && found.value.compareTo(value) == 0) {
                return false;
            }
            int level = randomLevel();
            if (level > currentLevel) {
                for (int i = currentLevel; i < level; i++) {
                    preds[i] = head;
                }
                currentLevel = level;
            }
            Node<T> node = new Node<T>(value, level);
            for (int i = 0; i < level; i++) {
                node.next[i] = preds[i].next[i];
                preds[i].next[i] = node;
            }
            count++;
            return true;
        } finally {
            releaseWrite();
        }
    }

    /** Removes value; returns true if it was present. */
    @SuppressWarnings("unchecked")
    public boolean remove(T value) {
        if (value == null) {
            throw new NullPointerException();
        }
        acquireWrite();
        try {
            Node<T>[] preds = (Node<T>[]) new Node[MAX_LEVEL];
            Node<T> found = findGreaterOrEqual(value, preds);
            if (found == null || found.value.compareTo(value) != 0) {
                return false;
            }
            for (int i = 0; i < currentLevel; i++) {
                if (preds[i].next[i] == found) {
                    preds[i].next[i] = found.next[i];
                }
            }
            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            count--;
            return true;
        } finally {
            releaseWrite();
        }
    }

    /** Returns true if value is present. Multiple contains calls run concurrently. */
    public boolean contains(T value) {
        if (value == null) {
            throw new NullPointerException();
        }
        acquireRead();
        try {
            Node<T> found = findGreaterOrEqual(value, null);
            return found != null && found.value.compareTo(value) == 0;
        } finally {
            releaseRead();
        }
    }

    public int size() {
        acquireRead();
        try {
            return count;
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
            currentLevel = 1;
            count = 0;
        } finally {
            releaseWrite();
        }
    }

    /** Returns the smallest element, or null if the set is empty. */
    public T first() {
        acquireRead();
        try {
            Node<T> n = head.next[0];
            return n == null ? null : n.value;
        } finally {
            releaseRead();
        }
    }
}
