@SkipListSet.ThreadSafe
public class SkipListSet<E extends Comparable<E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;
    private Node<E> head;
    private int level;

    public SkipListSet() {
        head = new Node<>(null, MAX_LEVEL);
        level = 1;
    }

    private static class Node<E> {
        E key;
        Node<E>[] next;

        Node(E key, int level) {
            this.key = key;
            this.next = new Node[level];
        }
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && Math.random() < 0.5) {
            lvl++;
        }
        return lvl;
    }

    public synchronized boolean add(E key) {
        Node<E>[] update = new Node[MAX_LEVEL];
        Node<E> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }
        current = current.next[0];
        if (current == null || !key.equals(current.key)) {
            int lvl = randomLevel();
            if (lvl > level) {
                for (int i = level; i < lvl; i++) {
                    update[i] = head;
                }
                level = lvl;
            }
            Node<E> newNode = new Node<>(key, lvl);
            for (int i = 0; i < lvl; i++) {
                newNode.next[i] = update[i].next[i];
                update[i].next[i] = newNode;
            }
            return true;
        } else {
            return false;
        }
    }

    public synchronized boolean remove(E key) {
        Node<E>[] update = new Node[MAX_LEVEL];
        Node<E> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }
        current = current.next[0];
        if (current != null && key.equals(current.key)) {
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
        } else {
            return false;
        }
    }

    public synchronized boolean contains(E key) {
        Node<E> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }
        current = current.next[0];
        return current != null && key.equals(current.key);
    }
}
