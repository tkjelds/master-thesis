@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16;
    private final Node<K, V> head;
    private int level;

    public SkipListMap() {
        head = new Node<>(null, null, MAX_LEVEL);
        level = 1;
    }

    public V get(K key) {
        synchronized (head) {
            Node<K, V> current = head;
            for (int i = level - 1; i >= 0; --i) {
                while (current.forward[i] != null && current.forward[i].key.compareTo(key) < 0) {
                    current = current.forward[i];
                }
            }
            current = current.forward[0];
            if (current != null && current.key.compareTo(key) == 0) {
                return current.value;
            }
        }
        return null;
    }

    public V put(K key, V value) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        synchronized (head) {
            Node<K, V> current = head;
            for (int i = level - 1; i >= 0; --i) {
                while (current.forward[i] != null && current.forward[i].key.compareTo(key) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }
            current = current.forward[0];
            if (current != null && current.key.compareTo(key) == 0) {
                V oldValue = current.value;
                current.value = value;
                return oldValue;
            } else {
                int newLevel = randomLevel();
                if (newLevel > level) {
                    for (int i = level; i < newLevel; ++i) {
                        update[i] = head;
                    }
                    level = newLevel;
                }
                Node<K, V> newNode = new Node<>(key, value, newLevel);
                for (int i = 0; i < newLevel; ++i) {
                    newNode.forward[i] = update[i].forward[i];
                    update[i].forward[i] = newNode;
                }
            }
        }
        return null;
    }

    public boolean remove(K key) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        synchronized (head) {
            Node<K, V> current = head;
            for (int i = level - 1; i >= 0; --i) {
                while (current.forward[i] != null && current.forward[i].key.compareTo(key) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }
            current = current.forward[0];
            if (current != null && current.key.compareTo(key) == 0) {
                for (int i = 0; i < level; ++i) {
                    if (update[i].forward[i] != current) {
                        break;
                    }
                    update[i].forward[i] = current.forward[i];
                }
                while (level > 1 && head.forward[level - 1] == null) {
                    --level;
                }
                return true;
            }
        }
        return false;
    }

    private int randomLevel() {
        int level = 1;
        while (Math.random() < 0.5 && level < MAX_LEVEL) {
            ++level;
        }
        return level;
    }

    private static class Node<K, V> {
        final K key;
        V value;
        final int level;
        Node<K, V>[] forward;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.level = level;
            this.forward = new Node[level];
        }
    }
}
