import java.util.Comparator;

@SkipListNode.ThreadSafe
public class SkipListNode<T> {
    @interface ThreadSafe {}

    T value;
    SkipListNode<T>[] next;

    @SuppressWarnings("unchecked")
    public SkipListNode(T value, int height) {
        this.value = value;
        next = (SkipListNode<T>[]) new SkipListNode[height];
    }
}

@SkipListSet.ThreadSafe
public class SkipListSet<T> {
    @interface ThreadSafe {}

    private final Comparator<T> comparator;
    private SkipListNode<T> head;
    private int maxLevel;

    public SkipListSet(Comparator<T> comparator) {
        this.comparator = comparator;
        maxLevel = 1;
        head = new SkipListNode<>(null, maxLevel);
    }

    public boolean add(T value) {
        synchronized (head) {
            SkipListNode<T>[] update = new SkipListNode[maxLevel];
            SkipListNode<T> current = head;

            for (int i = maxLevel - 1; i >= 0; i--) {
                while (current.next[i] != null && comparator.compare(current.next[i].value, value) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            SkipListNode<T> newNode = new SkipListNode<>(value, randomLevel());
            int level = newNode.next.length;
            for (int i = 0; i < level; i++) {
                newNode.next[i] = update[i].next[i];
                update[i].next[i] = newNode;
            }

            return true; // Node added successfully
        }
    }

    public boolean remove(T value) {
        synchronized (head) {
            SkipListNode<T>[] update = new SkipListNode[maxLevel];
            SkipListNode<T> current = head;

            for (int i = maxLevel - 1; i >= 0; i--) {
                while (current.next[i] != null && comparator.compare(current.next[i].value, value) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            if (current.next[0] != null && comparator.compare(current.next[0].value, value) == 0) {
                SkipListNode<T> nodeToDelete = current.next[0];
                for (int i = 0; i < nodeToDelete.next.length; i++) {
                    update[i].next[i] = nodeToDelete.next[i];
                }
                return true; // Node removed successfully
            } else {
                return false; // Node not found
            }
        }
    }

    public boolean contains(T value) {
        synchronized (head) {
            SkipListNode<T> current = head;

            for (int i = maxLevel - 1; i >= 0; i--) {
                while (current.next[i] != null && comparator.compare(current.next[i].value, value) < 0) {
                    current = current.next[i];
                }
            }

            return current.next[0] != null && comparator.compare(current.next[0].value, value) == 0;
        }
    }

    private int randomLevel() {
        // Simple power-of-two RNG for level
        int lvl = 1;
        while (lvl < maxLevel && Math.random() < 0.5) {
            lvl++;
        }
        return lvl;
    }

    private void updateMaxLevel(int newLevel) {
        synchronized (head) {
            if (newLevel > maxLevel) {
                @SuppressWarnings("unchecked")
                SkipListNode<T>[] temp = (SkipListNode<T>[]) new SkipListNode[newLevel];
                System.arraycopy(head.next, 0, temp, 0, head.next.length);
                for (int i = head.next.length; i < newLevel; i++) {
                    temp[i] = null;
                }
                head.next = temp;
                maxLevel = newLevel;
            }
        }
    }
}
