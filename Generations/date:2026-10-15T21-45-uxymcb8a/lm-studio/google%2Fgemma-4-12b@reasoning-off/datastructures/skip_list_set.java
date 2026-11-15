@SkipListSet.ThreadSafe
class SkipListSet {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 16;
    private final Node head;
    private int size;

    private static class Node {
        final int value;
        Node[] next;

        Node(int value, int level) {
            this.value = value;
            this.next = new Node[level];
        }
    }

    public SkipListSet() {
        head = new Node(-1, MAX_LEVEL);
        size = 0;
    }

    public void add(int value) {
        Node[] update = new Node[MAX_LEVEL];
        Node curr = head;

        synchronized (head) {
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (curr.next[i] != null && curr.next[i].value < value) {
                    curr = curr.next[i];
                }
                update[i] = curr;
            }
            curr = (curr.next[0] != null) ? curr.next[0] : null;

            if (curr == null || curr.value > value) {
                int level = randomLevel();
                Node newNode = new Node(value, level);
                for (int i = 0; i < level; i++) {
                    newNode.next[i] = update[i].next[i];
                    update[i].next[i] = newNode;
                }
                size++;
            }
        }
    }

    public boolean contains(int value) {
        Node curr = head;
        synchronized (head) {
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (curr.next[i] != null && curr.next[i].value < value) {
                    curr = curr.next[i];
                }
            }
            Node target = curr.next[0];
            return target != null && target.value == value;
        }
    }

    public void remove(int value) {
        Node[] update = new Node[MAX_LEVEL];
        Node curr = head;

        synchronized (head) {
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (curr.next[i] != null && curr.next[i].value < value) {
                    curr = curr.next[i];
                }
                update[i] = curr;
            }
            Node target = curr.next[0];

            if (target != null && target.value == value) {
                for (int i = 0; i < MAX_LEVEL; i++) {
                    if (update[i].next[i] != target) {
                        break;
                    }
                    update[i].next[i] = target.next[i];
                }
                size--;
            }
        }
    }

    public int size() {
        synchronized (head) {
            return size;
        }
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && Math.random() < 0.5) {
            level++;
        }
        return level;
    }

    public static void main(String[] args) {
        // Implementation of logic can be tested here.
    }
}
