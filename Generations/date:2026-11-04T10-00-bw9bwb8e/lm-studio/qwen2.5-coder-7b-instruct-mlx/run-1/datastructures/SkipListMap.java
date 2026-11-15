@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}

    private static final float SKIP_LIST_P = 0.5f;
    private static final int MAX_LEVEL = 16;

    private class Node {
        K key;
        V value;
        Node[] next;

        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = new Node[level];
        }
    }

    private Node head;
    private int currentLevel;

    public SkipListMap() {
        head = new Node(null, null, MAX_LEVEL);
        currentLevel = 1;
    }

    public synchronized V put(K key, V value) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (current.next[i] != null && key.compareTo(current.next[i].key) > 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        Node newNode = new Node(key, value, randomLevel());
        for (int i = 0; i < newNode.level; i++) {
            newNode.next[i] = update[i].next[i];
            update[i].next[i] = newNode;
        }

        if (newNode.level > currentLevel) {
            for (int i = currentLevel; i < newNode.level; i++) {
                update[i].next[i] = newNode;
            }
            currentLevel = newNode.level;
        }

        return null; // Return old value if replaced, or null otherwise
    }

    public synchronized V get(K key) {
        Node current = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (current.next[i] != null && key.compareTo(current.next[i].key) > 0) {
                current = current.next[i];
            }
            if (current.next[i] != null && key.equals(current.next[i].key)) {
                return current.next[i].value;
            }
        }

        return null; // Key not found
    }

    public synchronized V remove(K key) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (current.next[i] != null && key.compareTo(current.next[i].key) > 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        Node nodeToRemove = current.next[0];
        if (nodeToRemove != null && key.equals(nodeToRemove.key)) {
            for (int i = 0; i < nodeToRemove.level; i++) {
                update[i].next[i] = nodeToRemove.next[i];
            }
            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            return nodeToRemove.value;
        }

        return null; // Key not found
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && Math.random() < SKIP_LIST_P) {
            level++;
        }
        return level;
    }
}
