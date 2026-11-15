@SkipListSet.ThreadSafe
public class SkipListSet {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16;

    private class Node {
        Object value;
        Node[] forward;

        Node(Object value) {
            this.value = value;
            this.forward = new Node[MAX_LEVEL];
        }
    }

    private final Node head;
    private int levelCount;
    private long seed;
    private final Object lock = new Object();

    public SkipListSet() {
        head = new Node(null);
        levelCount = 1;
        seed = System.currentTimeMillis();
    }

    private int generateRandomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && (nextRandom() % 2 == 0)) {
            level++;
        }
        return level;
    }

    private int nextRandom() {
        seed = (seed * 1103515245 + 12345) & 0xFFFFFFFFL;
        return (int) seed;
    }

    public boolean add(Object value) {
        if (value == null) return false;
        synchronized (lock) {
            Node[] update = new Node[MAX_LEVEL];
            Node curr = head;

            for (int i = levelCount - 1; i >= 0; i--) {
                while (curr.forward[i] != null && compare(curr.forward[i].value, value) < 0) {
                    curr = curr.forward[i];
                }
                update[i] = curr;
            }

            curr = curr.forward[0];

            if (curr != null && compare(curr.value, value) == 0) {
                return false;
            }

            int level = generateRandomLevel();
            if (level > levelCount) {
                for (int i = levelCount; i < level; i++) {
                    update[i] = head;
                }
                levelCount = level;
            }

            Node newNode = new Node(value);
            for (int i = 0; i < level; i++) {
                newNode.forward[i] = update[i].forward[i];
                update[i].forward[i] = newNode;
            }

            return true;
        }
    }

    public boolean contains(Object value) {
        if (value == null) return false;
        synchronized (lock) {
            Node curr = head;
            for (int i = levelCount - 1; i >= 0; i--) {
                while (curr.forward[i] != null && compare(curr.forward[i].value, value) < 0) {
                    curr = curr.forward[i];
                }
            }
            curr = curr.forward[0];

            if (curr != null && compare(curr.value, value) == 0) {
                return true;
            }
            return false;
        }
    }

    public boolean remove(Object value) {
        if (value == null) return false;
        synchronized (lock) {
            Node[] update = new Node[MAX_LEVEL];
            Node curr = head;

            for (int i = levelCount - 1; i >= 0; i--) {
                while (curr.forward[i] != null && compare(curr.forward[i].value, value) < 0) {
                    curr = curr.forward[i];
                }
                update[i] = curr;
            }

            curr = curr.forward[0];

            if (curr != null && compare(curr.value, value) == 0) {
                for (int i = 0; i < levelCount; i++) {
                    if (update[i].forward[i] != curr) {
                        break;
                    }
                    update[i].forward[i] = curr.forward[i];
                }
                return true;
            }
            return false;
        }
    }

    private int compare(Object o1, Object o2) {
        if (o1 == null || o2 == null) return 0;
        if (o1.equals(o2)) return 0;
        return ((Comparable) o1).compareTo(o2);
    }

    public boolean containsAny() {
        synchronized (lock) {
            return head.forward[0] != null;
        }
    }
}
