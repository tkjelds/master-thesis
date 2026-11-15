import java.lang.*;

@SkipListSet.ThreadSafe
public class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 24;

    private static final class Link {
        volatile Node ref;
    }

    private static final class Node {
        final Object item;
        final int topLevel;
        final Link[] next;
        volatile boolean marked;
        volatile boolean fullyLinked;

        Node(Object item, int topLevel) {
            this.item = item;
            this.topLevel = topLevel;
            this.next = new Link[topLevel + 1];
            for (int i = 0; i <= topLevel; i++) {
                next[i] = new Link();
            }
        }
    }

    private final Node head;
    private final Node tail;

    private final Object rngLock = new Object();
    private long seed = 0x9E3779B97F4A7C15L ^ System.nanoTime();

    private final Object sizeLock = new Object();
    private int size = 0;

    public SkipListSet() {
        head = new Node(null, MAX_LEVEL - 1);
        tail = new Node(null, MAX_LEVEL - 1);
        for (int i = 0; i < MAX_LEVEL; i++) {
            head.next[i].ref = tail;
        }
        head.fullyLinked = true;
        tail.fullyLinked = true;
    }

    @SuppressWarnings("unchecked")
    private int cmp(Node n, Object item) {
        if (n == head) return -1;
        if (n == tail) return 1;
        return ((Comparable<Object>) n.item).compareTo(item);
    }

    private int randomLevel() {
        long x;
        synchronized (rngLock) {
            x = seed;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            if (x == 0) x = 0x9E3779B97F4A7C15L;
            seed = x;
        }
        int level = 0;
        while (level < MAX_LEVEL - 1 && (x & 1L) == 1L) {
            level++;
            x >>>= 1;
        }
        return level;
    }

    private int find(Object item, Node[] preds, Node[] succs) {
        int lFound = -1;
        Node pred = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            Node curr = pred.next[level].ref;
            while (cmp(curr, item) < 0) {
                pred = curr;
                curr = pred.next[level].ref;
            }
            if (lFound == -1 && curr != tail && cmp(curr, item) == 0) {
                lFound = level;
            }
            preds[level] = pred;
            succs[level] = curr;
        }
        return lFound;
    }

    private boolean lockAndInsert(Node newNode, Node[] preds, Node[] succs, int level, int top) {
        if (level > top) {
            for (int i = 0; i <= top; i++) {
                newNode.next[i].ref = succs[i];
            }
            for (int i = 0; i <= top; i++) {
                preds[i].next[i].ref = newNode;
            }
            synchronized (newNode) {
                newNode.fullyLinked = true;
                newNode.notifyAll();
            }
            return true;
        }
        Node pred = preds[level];
        Node succ = succs[level];
        synchronized (pred) {
            if (pred.marked || succ.marked || pred.next[level].ref != succ) {
                return false;
            }
            return lockAndInsert(newNode, preds, succs, level + 1, top);
        }
    }

    private boolean lockAndUnlink(Node victim, Node[] preds, int level, int top) {
        if (level > top) {
            for (int i = top; i >= 0; i--) {
                preds[i].next[i].ref = victim.next[i].ref;
            }
            return true;
        }
        Node pred = preds[level];
        synchronized (pred) {
            if (pred.marked || pred.next[level].ref != victim) {
                return false;
            }
            return lockAndUnlink(victim, preds, level + 1, top);
        }
    }

    public boolean add(E item) {
        if (item == null) throw new NullPointerException();
        int topLevel = randomLevel();
        Node[] preds = new Node[MAX_LEVEL];
        Node[] succs = new Node[MAX_LEVEL];
        while (true) {
            int lFound = find(item, preds, succs);
            if (lFound != -1) {
                Node found = succs[lFound];
                if (!found.marked) {
                    boolean interrupted = false;
                    synchronized (found) {
                        while (!found.fullyLinked) {
                            try {
                                found.wait();
                            } catch (InterruptedException e) {
                                interrupted = true;
                            }
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                    return false;
                }
                continue;
            }
            Node newNode = new Node(item, topLevel);
            if (lockAndInsert(newNode, preds, succs, 0, topLevel)) {
                synchronized (sizeLock) {
                    size++;
                }
                return true;
            }
        }
    }

    public boolean remove(E item) {
        if (item == null) throw new NullPointerException();
        Node[] preds = new Node[MAX_LEVEL];
        Node[] succs = new Node[MAX_LEVEL];
        Node victim = null;
        boolean isMarked = false;
        int topLevel = -1;
        while (true) {
            int lFound = find(item, preds, succs);
            if (!isMarked) {
                if (lFound == -1) return false;
                Node cand = succs[lFound];
                if (!(cand.fullyLinked && cand.topLevel == lFound && !cand.marked)) {
                    return false;
                }
                synchronized (cand) {
                    if (cand.marked) return false;
                    cand.marked = true;
                }
                victim = cand;
                topLevel = cand.topLevel;
                isMarked = true;
            }
            synchronized (victim) {
                if (lockAndUnlink(victim, preds, 0, topLevel)) {
                    synchronized (sizeLock) {
                        size--;
                    }
                    return true;
                }
            }
        }
    }

    public boolean contains(E item) {
        if (item == null) throw new NullPointerException();
        Node[] preds = new Node[MAX_LEVEL];
        Node[] succs = new Node[MAX_LEVEL];
        int lFound = find(item, preds, succs);
        return lFound != -1 && succs[lFound].fullyLinked && !succs[lFound].marked;
    }

    public int size() {
        synchronized (sizeLock) {
            return size;
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
