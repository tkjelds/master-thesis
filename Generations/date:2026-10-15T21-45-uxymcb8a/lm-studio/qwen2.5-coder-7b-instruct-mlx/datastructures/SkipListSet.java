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

    public boolean add(E value) {
        synchronized (head) {
            Node<E>[] update = new Node[MAX_LEVEL];
            Node<E> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null && current.forward[i].value.compareTo(value) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }

            current = current.forward[0];

            if (current != null && current.value.equals(value)) {
                return false;
            }

            int newLevel = randomLevel();
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    update[i] = head;
                }
                level = newLevel;
            }

            Node<E> newNode = new Node<>(value, newLevel);
            for (int i = 0; i < newLevel; i++) {
                newNode.forward[i] = update[i].forward[i];
                update[i].forward[i] = newNode;
            }

            return true;
        }
    }

    public boolean remove(E value) {
        synchronized (head) {
            Node<E>[] update = new Node[MAX_LEVEL];
            Node<E> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null && current.forward[i].value.compareTo(value) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }

            current = current.forward[0];

            if (current != null && current.value.equals(value)) {
                for (int i = 0; i < level; i++) {
                    if (update[i].forward[i] != current) {
                        break;
                    }
                    update[i].forward[i] = current.forward[i];
                }

                while (level > 1 && head.forward[level - 1] == null) {
                    level--;
                }

                return true;
            }

            return false;
        }
    }

    public boolean contains(E value) {
        synchronized (head) {
            Node<E> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null && current.forward[i].value.compareTo(value) < 0) {
                    current = current.forward[i];
                }
            }

            current = current.forward[0];

            return current != null && current.value.equals(value);
        }
    }

    private int randomLevel() {
        int level = 1;
        while (Math.random() < 0.5 && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }

    private static class Node<E> {
        E value;
        Node<E>[] forward;

        @SuppressWarnings("unchecked")
        Node(E value, int level) {
            this.value = value;
            forward = new Node[level];
        }
    }
}
