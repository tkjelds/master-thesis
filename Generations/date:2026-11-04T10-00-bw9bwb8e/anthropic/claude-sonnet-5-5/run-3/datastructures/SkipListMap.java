import java.lang.*;

@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    // A link holds a volatile reference so that readers see consistent pointers
    // without taking any lock.
    private static final class Link<K extends Comparable<K>, V> {
        volatile Node<K, V> ref;
    }

    private static final class Node<K extends Comparable<K>, V> {
        final K key;
        volatile V value;
        final Link<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int levels) {
            this.key = key;
            this.value = value;
            this.next = (Link<K, V>[]) new Link[levels];
            for (int i = 0; i < levels; i++) {
                this.next[i] = new Link<K, V>();
            }
        }
    }

    private final Node<K, V> head;
    private final Object writeLock = new Object();
    private volatile int topLevel = 1;   // modified only under writeLock
    private volatile int count = 0;      // modified only under writeLock
    private long seed;                   // guarded by writeLock

    public SkipListMap() {
        head = new Node<K, V>(null, null, MAX_LEVEL);
        seed = 0x9E3779B97F4A7C15L ^ System.nanoTime();
        if (seed == 0) {
            seed = 1;
        }
    }

    // Must be called with writeLock held.
    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL) {
            long x = seed;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            seed = x;
            if ((x & 1L) == 0) {
                break;
            }
            level++;
        }
        return level;
    }

    // Lock-free lookup; relies on volatile links and never-cleared next pointers
    // of removed nodes.
    private Node<K, V> findNode(K key) {
        Node<K, V> curr = head;
        for (int i = topLevel - 1; i >= 0; i--) {
            Node<K, V> nxt = curr.next[i].ref;
            while (nxt != null && nxt.key.compareTo(key) < 0) {
                curr = nxt;
                nxt = curr.next[i].ref;
            }
        }
        Node<K, V> cand = curr.next[0].ref;
        if (cand != null && cand.key.compareTo(key) == 0) {
            return cand;
        }
        return null;
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        Node<K, V> n = findNode(key);
        return n == null ? null : n.value;
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        return findNode(key) != null;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] findPreds(K key) {
        Node<K, V>[] preds = (Node<K, V>[]) new Node[MAX_LEVEL];
        for (int i = 0; i < MAX_LEVEL; i++) {
            preds[i] = head;
        }
        Node<K, V> curr = head;
        for (int i = topLevel - 1; i >= 0; i--) {
            Node<K, V> nxt = curr.next[i].ref;
            while (nxt != null && nxt.key.compareTo(key) < 0) {
                curr = nxt;
                nxt = curr.next[i].ref;
            }
            preds[i] = curr;
        }
        return preds;
    }

    private V putInternal(K key, V value, boolean onlyIfAbsent) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        synchronized (writeLock) {
            Node<K, V>[] preds = findPreds(key);
            Node<K, V> cand = preds[0].next[0].ref;
            if (cand != null && cand.key.compareTo(key) == 0) {
                V old = cand.value;
                if (!onlyIfAbsent) {
                    cand.value = value;
                }
                return old;
            }
            int level = randomLevel();
            Node<K, V> node = new Node<K, V>(key, value, level);
            for (int i = 0; i < level; i++) {
                node.next[i].ref = preds[i].next[i].ref;
            }
            for (int i = 0; i < level; i++) {
                preds[i].next[i].ref = node;
            }
            if (level > topLevel) {
                topLevel = level;
            }
            count = count + 1;
            return null;
        }
    }

    public V put(K key, V value) {
        return putInternal(key, value, false);
    }

    public V putIfAbsent(K key, V value) {
        return putInternal(key, value, true);
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        synchronized (writeLock) {
            Node<K, V>[] preds = findPreds(key);
            Node<K, V> target = preds[0].next[0].ref;
            if (target == null || target.key.compareTo(key) != 0) {
                return null;
            }
            for (int i = target.next.length - 1; i >= 0; i--) {
                if (preds[i].next[i].ref == target) {
                    preds[i].next[i].ref = target.next[i].ref;
                }
            }
            count = count - 1;
            return target.value;
        }
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public void clear() {
        synchronized (writeLock) {
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i].ref = null;
            }
            topLevel = 1;
            count = 0;
        }
    }
}
