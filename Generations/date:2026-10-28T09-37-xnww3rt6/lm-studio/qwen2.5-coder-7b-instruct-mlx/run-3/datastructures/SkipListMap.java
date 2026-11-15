```java
public class SkipListMap<K extends Comparable<? super K>, V> {
    private static final int MAX_LEVEL = 16;
    private Node<K, V> head;
    private int level;

    public SkipListMap() {
        head = new Node<>(Integer.MIN_VALUE, null, MAX_LEVEL);
        level = 0;
    }

    public V put(K key, V value) {
        synchronized (head) {
            Node<K, V> current = head;
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
            for (int i = level; i >= 0; --i) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            Node<K, V> node = current.next[0];
            if (node == null || !node.key.equals(key)) {
                int newLevel = randomLevel();
                if (newLevel > level) {
                    for (int i = level + 1; i <= newLevel; ++i) {
                        update[i] = head;
                    }
                    level = newLevel;
                }

                node = new Node<>(key, value, newLevel);
                for (int i = 0; i <= newLevel; ++i) {
                    node.next[i] = update[i].next[i];
                    update[i].next[i] = node;
                }
            } else {
                V oldValue = node.value;
                node.value = value;
                return oldValue;
            }
        }

        return null;
    }

    public V get(K key) {
        Node<K, V> current = head;
        synchronized (head) {
            for (int i = level; i >= 0; --i) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
            }

            Node<K, V> node = current.next[0];
            if (node != null && node.key.equals(key)) {
                return node.value;
            }
        }

        return null;
    }

    public boolean containsKey(K key) {
        Node<K, V> current = head;
        synchronized (head) {
            for (int i = level; i >= 0; --i) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
            }

            Node<K, V> node = current.next[0];
            return node != null && node.key.equals(key);
        }
    }

    public V remove(K key) {
        synchronized (head) {
            Node<K, V> current = head;
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
            for (int i = level; i >= 0; --i) {
                while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            Node<K, V> node = current.next[0];
            if (node != null && node.key.equals(key)) {
                for (int i = 0; i <= level; ++i) {
                    if (update[i].next[i] == node) {
                        update[i].next[i] = node.next[i];
                    } else {
                        break;
                    }
                }

                while (level > 0 && head.next[level] == null) {
                    level--;
                }

                return node.value;
            }
        }

        return null;
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && Math.random() < 0.5) {
            level++;
        }
        return level;
    }

    private static class Node<K extends Comparable<? super K>, V> {
        K key;
        V value;
        @SuppressWarnings("unchecked")
        Node<K, V>[] next;

        public Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            next = (Node<K, V>[]) new Node[level];
        }
    }
}
```
