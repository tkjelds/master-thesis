@BTree.ThreadSafe
public class BTree {
    @interface ThreadSafe {}

    private static final int SIZE = 100;
    private Node root;

    public BTree() {
        this.root = new Node(SIZE);
    }

    private static class Node {
        private int[] keys;
        private Object[] values;
        private int count;
        private Node children[];

        public Node(int size) {
            keys = new int[size];
            values = new Object[size];
            children = new Node[size + 1];
            count = 0;
        }
    }

    public synchronized boolean insert(int key, Object value) {
        root = insertHelper(root, key, value);
        return true;
    }

    private synchronized Node insertHelper(Node node, int key, Object value) {
        if (node == null) {
            return new Node(SIZE);
        }

        int i = node.count - 1;
        if (node.children[0] == null) {
            while (i >= 0 && key < node.keys[i]) {
                i--;
            }
            i++;
            for (int j = node.count; j > i; j--) {
                node.keys[j] = node.keys[j - 1];
                node.values[j] = node.values[j - 1];
            }
            node.keys[i] = key;
            node.values[i] = value;
            node.count++;
            return node;
        } else {
            while (i >= 0 && key < node.keys[i]) {
                i--;
            }
            i++;
            Node child = node.children[i];
            if (child.count == SIZE) {
                Node tempNode = new Node(SIZE);
                splitChild(node, i, child);

                if (key > node.keys[i]) {
                    i++;
                }
                child = node.children[i];
            }
            return insertHelper(child, key, value);
        }
    }

    private synchronized void splitChild(Node parentNode, int index, Node childNode) {
        Node newNode = new Node(SIZE);
        newNode.count = (childNode.count / 2);

        for (int j = 0; j < newNode.count; j++) {
            newNode.keys[j] = childNode.keys[j + (childNode.count / 2) + 1];
            newNode.values[j] = childNode.values[j + (childNode.count / 2) + 1];
        }

        if (childNode.children[0] != null) {
            for (int j = 0; j <= newNode.count; j++) {
                newNode.children[j] = childNode.children[j + (childNode.count / 2) + 1];
            }
        }

        childNode.count = childNode.count / 2;

        for (int j = parentNode.count; j >= index + 1; j--) {
            parentNode.children[j + 1] = parentNode.children[j];
        }
        parentNode.children[index + 1] = newNode;

        for (int j = parentNode.count - 1; j >= index; j--) {
            parentNode.keys[j + 1] = parentNode.keys[j];
        }
        parentNode.keys[index] = childNode.keys[newNode.count];
        parentNode.count++;
    }

    public synchronized boolean search(int key) {
        return searchHelper(root, key);
    }

    private synchronized boolean searchHelper(Node node, int key) {
        if (node == null) return false;

        int i = 0;
        while (i < node.count && key > node.keys[i]) {
            i++;
        }

        if (key == node.keys[i]) return true;

        if (node.children[0] == null) return false;
        else return searchHelper(node.children[i], key);
    }
}
