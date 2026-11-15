public class ConcurrentSkipListSet {

    private static final int MAX_LEVEL = 16;
    private static final double P_FACTOR = 0.5;

    private class Node {
        private final int key;
        private Node[] next;

        public Node(int key, int level) {
            this.key = key;
            this.next = new Node[level];
        }
    }

    private final Node head;
    private int levelCount;
    private long size;

    public ConcurrentSkipListSet() {
        head = new Node(0, MAX_LEVEL);
        levelCount = 1;
    }

    public boolean add(int key) {
        Node[] update = new Node[MAX_LEVEL];
        Node curr = head;

        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            synchronized (head) {
                while (curr.next[i] != null && curr.next[i].key < key) {
                    curr = curr.next[i];
                }
            }
            update[i] = curr;
        }

        Node target = (update[0].next[0] != null) ? update[0].next[0] : null;

        synchronized (head) {
            if (target != null && target.key == key) {
                return false;
            }

            int level = randomLevel();
            if (level > levelCount) {
                for (int i = levelCount; i < level; i++) {
                    update[i] = head;
                }
                levelCount = level;
            }

            Node newNode = new Node(key, level);
            for (int i = 0; i < level; i++) {
                newNode.next[i] = update[i].next[i];
                update[i].next[i] = newNode;
            }
            size++;
            return true;
        }
    }

    public boolean contains(int key) {
        Node curr = head;
        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (curr.next[i] != null && curr.next[i].key < key) {
                curr = curr.next[i];
            }
        }
        Node target = curr.next[0];
        return target != null && target.key == key;
    }

    public boolean remove(int key) {
        Node[] update = new Node[MAX_LEVEL];
        Node curr = head;

        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (curr.next[i] != null && curr.next[i].key < key) {
                curr = curr.next[i];
            }
        }
        update[0] = curr;

        synchronized (head) {
            Node target = update[0].next[0];
            if (target == null || target.key != key) {
                return false;
            }

            for (int i = 0; i < MAX_LEVEL; i++) {
                if (update[i].next[i] != target) break;
            }

            // Re-verify the node at each level since it might be null or different
            // because of concurrent modification during search.
            for (int i = 0; i < MAX_LEVEL; i++) {
                if (update[i].next[i] == target) {
                    update[i].next[i] = target.next[i];
                }
            }
            size--;
            return true;
        }
    }

    public int size() {
        synchronized (head) {
            return (int) size;
        }
    }

    private int randomLevel() {
        int level = 1;
        while (Math.random() < P_FACTOR && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }

    public static void main(String[] args) {
        // Example usage would go here.
    }
}
