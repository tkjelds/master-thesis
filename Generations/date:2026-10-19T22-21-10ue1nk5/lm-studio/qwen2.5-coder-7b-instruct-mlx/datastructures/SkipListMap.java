@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;
    private Node<K, V> header;
    private int level;

    public SkipListMap() {
        this.header = new Node<>(null, null);
        this.level = 1;
    }

    private static class Node<K extends Comparable<K>, V> {
        K key;
        V value;
        Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.next = new Node[MAX_LEVEL];
        }
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && Math.random() < 0.5) {
            lvl++;
        }
        return lvl;
    }

    public synchronized V put(K key, V value) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        Node<K, V> current = header;

        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current == null || !current.key.equals(key)) {
            int newLevel = randomLevel();
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    update[i] = header;
                }
                level = newLevel;
            }

            current = new Node<>(key, value);
            for (int i = 0; i < newLevel; i++) {
                current.next[i] = update[i].next[i];
                update[i].next[i] = current;
            }
        } else {
            current.value = value;
        }

        return current.value;
    }

    @SuppressWarnings("unchecked")
    public synchronized V get(K key) {
        Node<K, V> current = header;

        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }

        current = current.next[0];

        if (current != null && current.key.equals(key)) {
            return current.value;
        }

        return null;
    }

    public synchronized V remove(K key) {
        Node<K, V>[] update = new Node[MAX_LEVEL];
        Node<K, V> current = header;

        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        V oldValue = null;

        if (current != null && current.key.equals(key)) {
            for (int i = 0; i < level; i++) {
                if (update[i].next[i] == current) {
                    update[i].next[i] = current.next[i];
                }
            }

            while (level > 1 && header.next[level - 1] == null) {
                level--;
            }

            oldValue = current.value;
        }

        return oldValue;
    }
}
