@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16;
    private static final double PROBABILITY = 0.5;

    private Node<K, V>[] nodes;
    private int size;
    private volatile boolean modified;

    public SkipListMap() {
        nodes = new Node[MAX_LEVEL + 1];
        for (int i = 0; i <= MAX_LEVEL; i++) {
            nodes[i] = new Node<>(null, null, Integer.MAX_VALUE);
        }
    }

    public void put(K key, V value) {
        int[] update = new int[MAX_LEVEL + 1];
        Node<K, V> current = nodes[0];

        for (int i = MAX_LEVEL; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current.index;
        }

        current = current.next[0];

        if (current == null || key.compareTo(current.key) != 0) {
            int level = randomLevel();
            Node<K, V> newNode = new Node<>(key, value, level);

            for (int i = 0; i < level; i++) {
                newNode.next[i] = nodes[i].next[update[i]].next[i];
                nodes[i].next[update[i]].next[i] = newNode;
            }

            modified = true;
        } else {
            current.value = value;
        }
    }

    public V get(K key) {
        Node<K, V> current = nodes[0];

        for (int i = MAX_LEVEL; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }

        current = current.next[0];

        if (current != null && key.compareTo(current.key) == 0) {
            return current.value;
        }
        return null;
    }

    public V remove(K key) {
        int[] update = new int[MAX_LEVEL + 1];
        Node<K, V> current = nodes[0];

        for (int i = MAX_LEVEL; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current.index;
        }

        current = current.next[0];

        if (current != null && key.compareTo(current.key) == 0) {
            for (int i = 0; i <= MAX_LEVEL; i++) {
                if (nodes[i].next[update[i]].next[i] != current) break;
                nodes[i].next[update[i]].next[i] = current.next[i];
            }

            modified = true;
            return current.value;
        }
        return null;
    }

    public boolean containsKey(K key) {
        Node<K, V> current = nodes[0];

        for (int i = MAX_LEVEL; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }

        current = current.next[0];

        return current != null && key.compareTo(current.key) == 0;
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    private int randomLevel() {
        int level = 0;
        while (level < MAX_LEVEL && Math.random() < PROBABILITY) {
            level++;
        }
        return level;
    }

    private static class Node<K, V> {
        K key;
        V value;
        int level;
        Node<K, V>[] next;

        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.level = level;
            next = new Node[MAX_LEVEL + 1];
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i <= MAX_LEVEL; i++) {
                nodes[i] = new Node<>(null, null, Integer.MAX_VALUE);
            }
            size = 0;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void waitForModification() throws InterruptedException {
        synchronized (this) {
            while (!modified) {
                this.wait();
            }
            modified = false;
        }
    }

    public void notifyAllModifications() {
        synchronized (this) {
            this.notifyAll();
        }
    }

    public void waitForSize(int expectedSize) throws InterruptedException {
        synchronized (this) {
            while (size != expectedSize) {
                this.wait();
            }
        }
    }

    public void notifySizeChange() {
        synchronized (this) {
            this.notifyAll();
        }
    }

    public void waitForEmpty() throws InterruptedException {
        synchronized (this) {
            while (!isEmpty()) {
                this.wait();
            }
        }
    }

    public void notifyNotEmpty() {
        synchronized (this) {
            this.notifyAll();
        }
    }

    public void waitForNonEmpty() throws InterruptedException {
        synchronized (this) {
            while (isEmpty()) {
                this.wait();
            }
        }
    }

    public void notifyNotFull() {
        // SkipListMap is unbounded, so no need to implement this
    }
}
