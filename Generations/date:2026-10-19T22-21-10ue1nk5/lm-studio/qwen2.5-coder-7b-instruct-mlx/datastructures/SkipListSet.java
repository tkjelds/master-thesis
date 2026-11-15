@SkipListSet.ThreadSafe
public class SkipListSet<E extends Comparable<E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;
    private Node<E> head;
    private int level;

    public SkipListSet() {
        head = new Node<>(Integer.MIN_VALUE, MAX_LEVEL);
        level = 1;
    }

    public boolean add(E e) {
        Node<E>[] update = new Node[MAX_LEVEL];
        synchronized (head) {
            Node<E> x = head;
            for (int i = level - 1; i >= 0; i--) {
                while (x.forward[i] != null && x.forward[i].data.compareTo(e) < 0)
                    x = x.forward[i];
                update[i] = x;
            }
            x = x.forward[0];
        }

        if (x != null && x.data.compareTo(e) == 0)
            return false;

        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++)
                update[i] = head;
            level = newLevel;
        }

        Node<E> newNode = new Node<>(e, newLevel);
        for (int i = 0; i < newLevel; i++) {
            newNode.forward[i] = update[i].forward[i];
            update[i].forward[i] = newNode;
        }

        return true;
    }

    public boolean remove(E e) {
        Node<E>[] update = new Node[MAX_LEVEL];
        synchronized (head) {
            Node<E> x = head;
            for (int i = level - 1; i >= 0; i--) {
                while (x.forward[i] != null && x.forward[i].data.compareTo(e) < 0)
                    x = x.forward[i];
                update[i] = x;
            }
            x = x.forward[0];
        }

        if (x == null || x.data.compareTo(e) != 0)
            return false;

        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] != x)
                break;
            update[i].forward[i] = x.forward[i];
        }

        while (level > 1 && head.forward[level - 1] == null)
            level--;

        return true;
    }

    public boolean contains(E e) {
        Node<E> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].data.compareTo(e) < 0)
                x = x.forward[i];
        }
        x = x.forward[0];
        return x != null && x.data.compareTo(e) == 0;
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && Math.random() < 0.5)
            lvl++;
        return lvl;
    }

    private static class Node<E extends Comparable<E>> {
        E data;
        Node<E>[] forward;

        @SuppressWarnings("unchecked")
        Node(E data, int level) {
            this.data = data;
            forward = new Node[level];
        }
    }
}
