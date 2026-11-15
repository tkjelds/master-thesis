```java
public class SkipListSet {
    private static final int MAX_LEVEL = 16;
    private Node head;
    private int level;

    public SkipListSet() {
        head = new Node(Integer.MIN_VALUE, MAX_LEVEL);
        level = 1;
    }

    public synchronized boolean add(int value) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;

        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].value < value) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current != null && current.value == value) {
            return false;
        }

        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                update[i] = head;
            }
            level = newLevel;
        }

        Node newNode = new Node(value, newLevel);
        for (int i = 0; i < newLevel; i++) {
            newNode.next[i] = update[i].next[i];
            update[i].next[i] = newNode;
        }

        return true;
    }

    public synchronized boolean remove(int value) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;

        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].value < value) {
                current = current.next[i];
            }
            update[i] = current;
        }

        current = current.next[0];

        if (current == null || current.value != value) {
            return false;
        }

        for (int i = 0; i < level; i++) {
            if (update[i].next[i] != current) {
                break;
            }
            update[i].next[i] = current.next[i];
        }

        while (level > 1 && head.next[level - 1] == null) {
            level--;
        }

        return true;
    }

    public synchronized boolean contains(int value) {
        Node current = head;

        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].value < value) {
                current = current.next[i];
            }
        }

        current = current.next[0];

        return current != null && current.value == value;
    }

    private int randomLevel() {
        int level = 1;
        while (Math.random() < 0.5 && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }

    private static class Node {
        int value;
        Node[] next;

        Node(int value, int level) {
            this.value = value;
            this.next = new Node[level];
        }
    }
}
```
