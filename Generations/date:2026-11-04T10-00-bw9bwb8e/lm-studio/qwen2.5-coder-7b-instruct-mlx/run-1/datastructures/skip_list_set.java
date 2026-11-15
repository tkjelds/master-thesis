@SkipListSet.ThreadSafe
class SkipListSet {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;
    private Node head;

    public SkipListSet() {
        head = new Node(Integer.MIN_VALUE, MAX_LEVEL);
    }

    public boolean add(int value) {
        synchronized (head) {
            Node[] update = new Node[MAX_LEVEL];
            Node current = head;

            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (current.forward[i] != null && current.forward[i].value < value) {
                    current = current.forward[i];
                }
                update[i] = current;
            }

            Node newNode = new Node(value, randomLevel());
            for (int i = 0; i < newNode.level; i++) {
                newNode.forward[i] = update[i].forward[i];
                update[i].forward[i] = newNode;
            }

            return true; // Always returns true in a simple SkipListSet implementation
        }
    }

    public boolean remove(int value) {
        synchronized (head) {
            Node[] update = new Node[MAX_LEVEL];
            Node current = head;

            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (current.forward[i] != null && current.forward[i].value < value) {
                    current = current.forward[i];
                }
                update[i] = current;
            }

            Node nodeToRemove = current.forward[0];
            if (nodeToRemove != null && nodeToRemove.value == value) {
                for (int i = 0; i < nodeToRemove.level; i++) {
                    update[i].forward[i] = nodeToRemove.forward[i];
                }
                return true;
            } else {
                return false; // Node not found
            }
        }
    }

    public boolean contains(int value) {
        synchronized (head) {
            Node current = head;

            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (current.forward[i] != null && current.forward[i].value < value) {
                    current = current.forward[i];
                }
            }

            Node nodeToCheck = current.forward[0];
            return nodeToCheck != null && nodeToCheck.value == value;
        }
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
        Node[] forward;

        Node(int value, int level) {
            this.value = value;
            this.forward = new Node[level];
        }
    }
}
