import java.lang.Comparable;

@ConcurrentSkipListSet.ThreadSafe
public class ConcurrentSkipListSet<T extends Comparable<T>> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 16;

    private static final class Ref<E extends Comparable<E>> {
        volatile Node<E> ref;
    }

    private static final class Node<E extends Comparable<E>> {
        final E key;
        final boolean isHead;
        final boolean isTail;
        final int topLevel;
        final Ref<E>[] next;
        volatile boolean marked;
        volatile boolean fullyLinked;

        @SuppressWarnings("unchecked")
        Node(E key, boolean isHead, boolean isTail, int topLevel) {
            this.key = key;
            this.isHead = isHead;
            this.isTail = isTail;
            this.topLevel = topLevel;
            this.next = (Ref<E>[]) new Ref[topLevel + 1];
            for (int i = 0; i <= topLevel; i++) {
                next[i] = new Ref<E>();
            }
        }
    }

    private final Node<T> head;
    private final Node<T> tail;

    private final Object rngLock = new Object();
    private long seed = 0x2545F4914F6CDD1DL;

    private final Object sizeLock = new Object();
    private int count = 0;

    public ConcurrentSkipListSet() {
        head = new Node<T>(null, true, false, MAX_LEVEL - 1);
        tail = new Node<T>(null, false, true, MAX_LEVEL - 1);
        for (int i = 0; i < MAX_LEVEL; i++) {
            head.next[i].ref = tail;
        }
        head.fullyLinked = true;
        tail.fullyLinked = true;
    }

    private int randomLevel() {
        long x;
        synchronized (rngLock) {
            x = seed;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            seed = x;
        }
        int level = 0;
        while (level < MAX_LEVEL - 1 && (x & 1L) == 1L) {
            level++;
            x >>>= 1;
        }
        return level;
    }

    // true if node's key is strictly less than key
    private boolean less(Node<T> node, T key) {
        if (node.isTail) return false;
        if (node.isHead) return true;
        return node.key.compareTo(key) < 0;
    }

    private boolean equalsKey(Node<T> node, T key) {
        if (node.isTail || node.isHead) return false;
        return node.key.compareTo(key) == 0;
    }

    private int find(T key, Node<T>[] preds, Node<T>[] succs) {
        int lFound = -1;
        Node<T> pred = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            Node<T> curr = pred.next[level].ref;
            while (less(curr, key)) {
                pred = curr;
                curr = pred.next[level].ref;
            }
            if (lFound == -1 && equalsKey(curr, key)) {
                lFound = level;
            }
            if (preds != null) {
                preds[level] = pred;
                succs[level] = curr;
            }
        }
        return lFound;
    }

    @SuppressWarnings("unchecked")
    public boolean add(T key) {
        if (key == null) throw new NullPointerException();
        int topLevel = randomLevel();
        Node<T>[] preds = (Node<T>[]) new Node[MAX_LEVEL];
        Node<T>[] succs = (Node<T>[]) new Node[MAX_LEVEL];
        Node<T> newNode = new Node<T>(key, false, false, topLevel);
        while (true) {
            int lFound = find(key, preds, succs);
            if (lFound != -1) {
                Node<T> nodeFound = succs[lFound];
                if (!nodeFound.marked) {
                    awaitLinkedOrMarked(nodeFound);
                    if (!nodeFound.marked) {
                        return false;
                    }
                }
                continue;
            }
            if (linkLevels(0, topLevel, preds, succs, newNode)) {
                synchronized (sizeLock) {
                    count++;
                }
                return true;
            }
        }
    }

    private void awaitLinkedOrMarked(Node<T> node) {
        boolean interrupted = false;
        synchronized (node) {
            while (!node.fullyLinked && !node.marked) {
                try {
                    node.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean linkLevels(int level, int topLevel, Node<T>[] preds, Node<T>[] succs,
                               Node<T> newNode) {
        if (level > topLevel) {
            for (int l = 0; l <= topLevel; l++) {
                newNode.next[l].ref = succs[l];
            }
            for (int l = 0; l <= topLevel; l++) {
                preds[l].next[l].ref = newNode;
            }
            synchronized (newNode) {
                newNode.fullyLinked = true;
                newNode.notifyAll();
            }
            return true;
        }
        Node<T> pred = preds[level];
        Node<T> succ = succs[level];
        synchronized (pred) {
            if (pred.marked || succ.marked || pred.next[level].ref != succ) {
                return false;
            }
            return linkLevels(level + 1, topLevel, preds, succs, newNode);
        }
    }

    @SuppressWarnings("unchecked")
    public boolean remove(T key) {
        if (key == null) throw new NullPointerException();
        Node<T>[] preds = (Node<T>[]) new Node[MAX_LEVEL];
        Node<T>[] succs = (Node<T>[]) new Node[MAX_LEVEL];
        Node<T> victim = null;
        boolean isMarked = false;
        int topLevel = -1;
        while (true) {
            int lFound = find(key, preds, succs);
            if (!isMarked) {
                if (lFound == -1) return false;
                Node<T> cand = succs[lFound];
                if (!(cand.fullyLinked && cand.topLevel == lFound && !cand.marked)) {
                    return false;
                }
                victim = cand;
                topLevel = victim.topLevel;
                synchronized (victim) {
                    if (victim.marked) {
                        return false;
                    }
                    victim.marked = true;
                    victim.notifyAll();
                }
                isMarked = true;
            }
            if (unlinkLevels(0, topLevel, preds, victim)) {
                synchronized (sizeLock) {
                    count--;
                }
                return true;
            }
        }
    }

    private boolean unlinkLevels(int level, int topLevel, Node<T>[] preds, Node<T> victim) {
        if (level > topLevel) {
            for (int l = topLevel; l >= 0; l--) {
                preds[l].next[l].ref = victim.next[l].ref;
            }
            return true;
        }
        Node<T> pred = preds[level];
        synchronized (pred) {
            if (pred.marked || pred.next[level].ref != victim) {
                return false;
            }
            return unlinkLevels(level + 1, topLevel, preds, victim);
        }
    }

    @SuppressWarnings("unchecked")
    public boolean contains(T key) {
        if (key == null) throw new NullPointerException();
        Node<T>[] preds = (Node<T>[]) new Node[MAX_LEVEL];
        Node<T>[] succs = (Node<T>[]) new Node[MAX_LEVEL];
        int lFound = find(key, preds, succs);
        return lFound != -1 && succs[lFound].fullyLinked && !succs[lFound].marked;
    }

    public int size() {
        synchronized (sizeLock) {
            return count;
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
