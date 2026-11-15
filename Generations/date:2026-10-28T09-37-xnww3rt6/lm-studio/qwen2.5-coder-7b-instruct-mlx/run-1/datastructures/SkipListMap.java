@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    private static final int LEVELS = 16;
    private Node<K, V> head;

    public SkipListMap() {
        head = new Node<>(null, null, LEVELS);
        for (int i = 0; i < LEVELS; i++) {
            head.next[i] = null;
        }
    }

    private Node<K, V> newNode(K key, V value, int level) {
        return new Node<>(key, value, level);
    }

    public V put(K key, V value) {
        Node<K, V>[] update = new Node[LEVELS];
        synchronized (head) {
            Node<K, V> current = head;
            for (int i = LEVELS - 1; i >= 0; --i) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }
            Node<K, V> node = current.next[0];
            if (node == null || !node.key.equals(key)) {
                int level = randomLevel();
                if (level > LEVELS - 1)
                    for (int i = LEVELS; i < level; ++i)
                        update[i] = head;
                node = newNode(key, value, level);
                for (int i = 0; i < level; ++i) {
                    node.next[i] = update[i].next[i];
                    update[i].next[i] = node;
                }
            } else {
                node.value = value;
            }
        }
        return node == null ? null : node.value;
    }

    public V get(K key) {
        synchronized (head) {
            Node<K, V> current = head;
            for (int i = LEVELS - 1; i >= 0; --i) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
            }
            Node<K, V> node = current.next[0];
            return node != null && node.key.equals(key) ? node.value : null;
        }
    }

    public V remove(K key) {
        Node<K, V>[] update = new Node[LEVELS];
        synchronized (head) {
            Node<K, V> current = head;
            for (int i = LEVELS - 1; i >= 0; --i) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }
            Node<K, V> node = current.next[0];
            if (node != null && node.key.equals(key)) {
                for (int i = 0; i < LEVELS; ++i) {
                    if (update[i].next[i] != node)
                        break;
                    update[i].next[i] = node.next[i];
                }
            }
            return node == null ? null : node.value;
        }
    }

    private int randomLevel() {
        int level = 1;
        while (level < LEVELS && Math.random() < 0.5) {
            ++level;
        }
        return level;
    }

    private static class Node<K extends Comparable<K>, V> {
        K key;
        V value;
        Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            next = (Node<K, V>[]) new Node[level];
        }
    }
}
