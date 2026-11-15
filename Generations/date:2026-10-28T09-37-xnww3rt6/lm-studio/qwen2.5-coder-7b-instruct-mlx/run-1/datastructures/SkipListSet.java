import java.util.Comparator;

@SkipListSet.ThreadSafe
public class SkipListSet<T> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16;
    private Node head;
    private Comparator<T> comparator;

    public SkipListSet(Comparator<T> comparator) {
        this.comparator = comparator;
        head = new Node(null, MAX_LEVEL);
    }

    public boolean add(T element) {
        synchronized (head) {
            Node[] update = new Node[MAX_LEVEL];
            findNode(element, update);

            if (update[0].forward[0] != null) {
                return false;
            }

            int level = randomLevel();
            if (level > head.level) {
                for (int i = head.level; i < level; i++) {
                    update[i] = head;
                }
                head.level = level;
            }

            Node newNode = new Node(element, level);
            for (int i = 0; i < level; i++) {
                newNode.forward[i] = update[i].forward[i];
                update[i].forward[i] = newNode;
            }
            return true;
        }
    }

    public boolean remove(T element) {
        synchronized (head) {
            Node[] update = new Node[MAX_LEVEL];
            findNode(element, update);

            if (update[0].forward[0] == null) {
                return false;
            }

            Node nodeToRemove = update[0].forward[0];
            for (int i = 0; i < nodeToRemove.level; i++) {
                update[i].forward[i] = nodeToRemove.forward[i];
            }

            int maxHeightRemoved = nodeToRemove.level;
            for (int i = nodeToRemove.level - 1; i >= 0; i--) {
                if (head.forward[i] != null) {
                    maxHeightRemoved = i + 1;
                } else {
                    break;
                }
            }

            for (int i = maxHeightRemoved; i < head.level; i++) {
                update[i] = null;
            }

            head.level = maxHeightRemoved - 1;
            return true;
        }
    }

    public boolean contains(T element) {
        synchronized (head) {
            Node[] update = new Node[MAX_LEVEL];
            findNode(element, update);
            return update[0].forward[0] != null;
        }
    }

    private Node findNode(T element, Node[] update) {
        int i = head.level;
        Node x = head;

        while (true) {
            while (x.forward[i] != null && comparator.compare(x.forward[i].element, element) < 0) {
                x = x.forward[i];
            }
            update[i] = x;
            if (i == 0) {
                return x;
            }
            i--;
        }
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && Math.random() < 0.5) {
            level++;
        }
        return level;
    }

    private class Node {
        T element;
        Node[] forward;

        public Node(T element, int level) {
            this.element = element;
            this.forward = new Node[level];
        }
    }
}
