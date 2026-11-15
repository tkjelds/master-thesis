import java.util.NoSuchElementException;

@Node.ThreadSafe
class Node<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    K key;
    V value;
    Node<K, V>[] next;

    @SuppressWarnings("unchecked")
    Node(K key, V value, int height) {
        this.key = key;
        this.value = value;
        this.next = new Node[height];
    }
}

@SkipListMap.ThreadSafe
@SuppressWarnings("unchecked")
public class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    private static final double P = 0.5;
    private Node<K, V> head;
    private int maxLevel;

    public SkipListMap() {
        this.head = new Node<>(null, null, 1);
        this.maxLevel = 0;
    }

    private int randomLevel() {
        int level = 1;
        while (Math.random() < P && level < maxLevel) {
            level++;
        }
        return level;
    }

    public synchronized V put(K key, V value) {
        Node<K, V>[] update = new Node[maxLevel + 1];
        Node<K, V> current = head;

        for (int i = maxLevel; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current == null || !current.key.equals(key)) {
            int newLevel = randomLevel();
            if (newLevel > maxLevel) {
                for (int i = maxLevel + 1; i <= newLevel; i++) {
                    update[i] = head;
                }
                maxLevel = newLevel;
            }

            Node<K, V> newNode = new Node<>(key, value, newLevel);
            for (int i = 0; i < newNode.next.length; i++) {
                newNode.next[i] = update[i].next[i];
                update[i].next[i] = newNode;
            }
        } else {
            V oldValue = current.value;
            current.value = value;
            return oldValue;
        }

        return null;
    }

    public synchronized V get(K key) {
        Node<K, V> current = head;
        for (int i = maxLevel; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }

        current = current.next[0];

        if (current != null && current.key.equals(key)) {
            return current.value;
        } else {
            throw new NoSuchElementException("Key not found: " + key);
        }
    }

    public synchronized V remove(K key) {
        Node<K, V>[] update = new Node[maxLevel + 1];
        Node<K, V> current = head;

        for (int i = maxLevel; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current != null && current.key.equals(key)) {
            V oldValue = current.value;
            for (int i = 0; i < current.next.length; i++) {
                update[i].next[i] = current.next[i];
            }

            while (maxLevel > 0 && head.next[maxLevel] == null) {
                maxLevel--;
            }

            return oldValue;
        } else {
            throw new NoSuchElementException("Key not found: " + key);
        }
    }

    public synchronized boolean containsKey(K key) {
        Node<K, V> current = head;
        for (int i = maxLevel; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }

        current = current.next[0];

        return current != null && current.key.equals(key);
    }

    public synchronized int size() {
        Node<K, V> current = head.next[0];
        int count = 0;
        while (current != null) {
            current = current.next[0];
            count++;
        }
        return count;
    }

    public synchronized boolean isEmpty() {
        return head.next[0] == null;
    }
}
