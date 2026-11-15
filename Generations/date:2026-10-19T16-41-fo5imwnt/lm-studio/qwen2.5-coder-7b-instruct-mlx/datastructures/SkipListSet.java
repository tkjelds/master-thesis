@SkipListSet.ThreadSafe
public class SkipListSet {
    @interface ThreadSafe {}

    private static final double P = 0.5;
    private Node head;

    public SkipListSet() {
        head = new Node(Long.MIN_VALUE, 32);
    }

    public boolean add(long key) {
        synchronized (head) {
            Node pred = head;
            Node curr = head.right;
            int level = head.level;

            for (int i = level - 1; i >= 0; i--) {
                while (curr != null && curr.key < key) {
                    pred = curr;
                    curr = curr.right[i];
                }
                if (curr != null && curr.key == key) {
                    return false;
                }

                Node newNode = new Node(key, i);
                newNode.right[i] = curr;

                if (pred.right[i] == curr) {
                    pred.right[i] = newNode;
                }
            }

            for (int i = 0; Math.random() < P && i < level; i++) {
                while (head.level - 1 > i && pred == head) {
                    pred = head;
                    curr = head.right[i];
                }
                Node updateNode = new Node(key, i);
                while (pred.right[i] != curr) {
                    updateNode.right[i] = curr;
                    if (!curr.up.equals(updateNode)) {
                        curr.up = updateNode;
                    }
                }
                pred.right[i] = updateNode;
                pred = updateNode;
            }

            head.up = null;

            if (head.level < level + 1) {
                while (head.right[head.level] != null) {
                    head.up = new Node(head.key, head.level + 1);
                }
            }

            return true;
        }
    }

    public boolean remove(long key) {
        synchronized (head) {
            Node pred = head;
            Node curr = head.right[0];

            while (curr != null && curr.key < key) {
                pred = curr;
                curr = curr.right[0];
            }

            if (curr != null && curr.key == key) {
                while (pred.right[0] != curr || !curr.valid) {
                    pred = curr;
                    curr = curr.right[0];
                }
                while (curr != null) {
                    pred.right[curr.level] = curr.right[curr.level];
                    if (!curr.up.equals(pred)) {
                        pred.up = null;
                    }
                    curr.valid = false;
                    pred = curr;
                    curr = curr.up;
                }

                while (head.level != 0 && head.right[head.level] == null) {
                    head.up = null;
                    head.level--;
                }

                return true;
            }

            return false;
        }
    }

    public boolean contains(long key) {
        synchronized (head) {
            Node pred = head;
            Node curr = head.right[0];

            while (curr != null && curr.key < key) {
                pred = curr;
                curr = curr.right[0];
            }

            return curr != null && curr.key == key;
        }
    }

    private static class Node {
        long key;
        boolean valid = true;
        int level;
        Node[] right;
        Node up;

        public Node(long key, int level) {
            this.key = key;
            this.level = level;
            this.right = new Node[level];
        }
    }
}
