import java.util.concurrent.atomic.AtomicReferenceArray;

@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;
    private Node<K, V> head;
    private Node<K, V>[] tail;
    private int level;

    public SkipListMap() {
        head = new Node<>(null, null, MAX_LEVEL);
        tail = new Node[MAX_LEVEL];
        for (int i = 0; i < MAX_LEVEL; i++) {
            tail[i] = new Node<>(null, null, 1);
        }
        level = 1;
    }

    public V put(K key, V value) {
        Node<K, V> update[] = new Node[MAX_LEVEL];
        int klevel = randomLevel();
        if (klevel > level) {
            for (int i = level; i < klevel; i++) {
                update[i] = head;
            }
            level = klevel;
        }

        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].key.compareTo(key) < 0) {
                x = x.forward[i];
            }
            update[i] = x;
        }

        x = x.forward[0];
        if (x == null || !x.key.equals(key)) {
            Node<K, V> newNode = new Node<>(key, value, klevel);
            for (int i = 0; i < klevel; i++) {
                newNode.forward[i] = update[i].forward[i];
                update[i].forward[i] = newNode;
            }
        } else {
            V oldValue = x.value;
            x.value = value;
            return oldValue;
        }
        return null;
    }

    public V get(K key) {
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].key.compareTo(key) < 0) {
                x = x.forward[i];
            }
        }

        x = x.forward[0];
        return (x != null && x.key.equals(key)) ? x.value : null;
    }

    public V remove(K key) {
        Node<K, V> update[] = new Node[MAX_LEVEL];
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].key.compareTo(key) < 0) {
                x = x.forward[i];
            }
            update[i] = x;
        }

        x = x.forward[0];
        if (x != null && x.key.equals(key)) {
            for (int i = 0; i < level; i++) {
                if (update[i].forward[i] != x) {
                    break;
                }
                update[i].forward[i] = x.forward[i];
            }

            while (level > 1 && head.forward[level - 1] == null) {
                level--;
            }
        }
        return (x != null) ? x.value : null;
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && Math.random() < 0.5) {
            lvl++;
        }
        return lvl;
    }

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V>[] forward;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            forward = new Node[level];
        }
    }
}
