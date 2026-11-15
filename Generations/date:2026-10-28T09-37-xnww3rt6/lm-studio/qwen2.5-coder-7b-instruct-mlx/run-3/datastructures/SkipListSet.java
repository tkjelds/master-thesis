```java
public class SkipListSet<E extends Comparable<E>> {
    private static final int MAX_LEVEL = 16;
    private Node<E> head;
    private int level;

    public SkipListSet() {
        head = new Node<>(null, MAX_LEVEL);
        level = 1;
    }

    public boolean add(E e) {
        Node<E>[] update = new Node[MAX_LEVEL];
        synchronized (head) {
            Node<E> current = head;
            for (int i = level - 1; i >= 0; --i) {
                while (current.forward[i] != null && current.forward[i].element.compareTo(e) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }
            current = current.forward[0];
        }

        if (current != null && current.element.equals(e)) {
            return false;
        }

        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; ++i) {
                update[i] = head;
            }
            level = newLevel;
        }

        Node<E> newNode = new Node<>(e, newLevel);
        for (int i = 0; i < newLevel; ++i) {
            newNode.forward[i] = update[i].forward[i];
            update[i].forward[i] = newNode;
        }

        return true;
    }

    public boolean remove(E e) {
        Node<E>[] update = new Node[MAX_LEVEL];
        synchronized (head) {
            Node<E> current = head;
            for (int i = level - 1; i >= 0; --i) {
                while (current.forward[i] != null && current.forward[i].element.compareTo(e) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }
            current = current.forward[0];
        }

        if (current != null && current.element.equals(e)) {
            for (int i = 0; i < level; ++i) {
                if (update[i].forward[i] != current) break;
                update[i].forward[i] = current.forward[i];
            }
            while (level > 1 && head.forward[level - 1] == null) {
                --level;
            }
            return true;
        }

        return false;
    }

    public boolean contains(E e) {
        Node<E> current = head;
        for (int i = level - 1; i >= 0; --i) {
            while (current.forward[i] != null && current.forward[i].element.compareTo(e) < 0) {
                current = current.forward[i];
            }
        }
        current = current.forward[0];
        return current != null && current.element.equals(e);
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && Math.random() < 0.5) {
            ++lvl;
        }
        return lvl;
    }

    private static class Node<E> {
        E element;
        Node<E>[] forward;

        @SuppressWarnings("unchecked")
        Node(E element, int level) {
            this.element = element;
            forward = new Node[level];
        }
    }
}
```
