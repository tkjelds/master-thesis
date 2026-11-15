interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        final boolean leaf;
        int count;

        Node(int keyCapacity, int childCapacity, boolean leaf) {
            this.keys = new Object[keyCapacity];
            this.children = new Node[childCapacity];
            this.leaf = leaf;
        }
    }

    private final int degree;
    private final int keyCapacity;
    private final int childCapacity;
    private final Order<T> order;
    private final Object lock = new Object();

    private Node root;
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || 2L * minimumDegree > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.degree = minimumDegree;
        this.childCapacity = 2 * minimumDegree;
        this.keyCapacity = childCapacity - 1;
        this.order = order;
    }

    public boolean contains(T item) {
        requireItem(item);
        synchronized (lock) {
            return containsInternal(item);
        }
    }

    public boolean add(T item) {
        requireItem(item);
        synchronized (lock) {
            if (containsInternal(item)) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Set size exceeds int capacity");
            }

            if (root == null) {
                root = new Node(keyCapacity, childCapacity, true);
                root.keys[0] = item;
                root.count = 1;
                size = 1;
                return true;
            }

            if (root.count == keyCapacity) {
                Node newRoot = new Node(keyCapacity, childCapacity, false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
            }

            insertNonFull(root, item);
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (lock) {
            if (!containsInternal(item)) {
                return false;
            }

            delete(root, item);
            size--;
            if (root.count == 0) {
                root = root.leaf ? null : root.children[0];
            }
            return true;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Empty tree");
            }
            Node node = root;
            while (!node.leaf) {
                node = node.children[0];
            }
            return key(node, 0);
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Empty tree");
            }
            Node node = root;
            while (!node.leaf) {
                node = node.children[node.count];
            }
            return key(node, node.count - 1);
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[size];
            if (root != null) {
                copyInOrder(root, result, 0);
            }
            return result;
        }
    }

    private void requireItem(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    @SuppressWarnings("unchecked")
    private T key(Node node, int index) {
        return (T) node.keys[index];
    }

    private boolean containsInternal(T item) {
        Node node = root;
        while (node != null) {
            int i = 0;
            while (i < node.count) {
                int comparison = order.compare(item, key(node, i));
                if (comparison == 0) {
                    return true;
                }
                if (comparison < 0) {
                    break;
                }
                i++;
            }
            node = node.leaf ? null : node.children[i];
        }
        return false;
    }

    private void splitChild(Node parent, int index) {
        Node left = parent.children[index];
        Node right = new Node(keyCapacity, childCapacity, left.leaf);
        right.count = degree - 1;

        for (int j = 0; j < degree - 1; j++) {
            right.keys[j] = left.keys[j + degree];
            left.keys[j + degree] = null;
        }
        if (!left.leaf) {
            for (int j = 0; j < degree; j++) {
                right.children[j] = left.children[j + degree];
                left.children[j + degree] = null;
            }
        }

        Object separator = left.keys[degree - 1];
        left.keys[degree - 1] = null;
        left.count = degree - 1;

        for (int j = parent.count; j > index; j--) {
            parent.children[j + 1] = parent.children[j];
        }
        parent.children[index + 1] = right;

        for (int j = parent.count - 1; j >= index; j--) {
            parent.keys[j + 1] = parent.keys[j];
        }
        parent.keys[index] = separator;
        parent.count++;
    }

    private void insertNonFull(Node node, T item) {
        while (!node.leaf) {
            int i = node.count - 1;
            while (i >= 0 && order.compare(item, key(node, i)) < 0) {
                i--;
            }
            i++;

            if (node.children[i].count == keyCapacity) {
                splitChild(node, i);
                if (order.compare(item, key(node, i)) > 0) {
                    i++;
                }
            }
            node = node.children[i];
        }

        int i = node.count - 1;
        while (i >= 0 && order.compare(item, key(node, i)) < 0) {
            node.keys[i + 1] = node.keys[i];
            i--;
        }
        node.keys[i + 1] = item;
        node.count++;
    }

    private void delete(Node node, T item) {
        int index = 0;
        while (index < node.count && order.compare(item, key(node, index)) > 0) {
            index++;
        }

        if (index < node.count && order.compare(item, key(node, index)) == 0) {
            if (node.leaf) {
                for (int j = index; j < node.count - 1; j++) {
                    node.keys[j] = node.keys[j + 1];
                }
                node.keys[node.count - 1] = null;
                node.count--;
                return;
            }

            Node left = node.children[index];
            Node right = node.children[index + 1];
            if (left.count >= degree) {
                Node predecessorNode = left;
                while (!predecessorNode.leaf) {
                    predecessorNode = predecessorNode.children[predecessorNode.count];
                }
                T predecessor = key(predecessorNode, predecessorNode.count - 1);
                node.keys[index] = predecessor;
                delete(left, predecessor);
            } else if (right.count >= degree) {
                Node successorNode = right;
                while (!successorNode.leaf) {
                    successorNode = successorNode.children[0];
                }
                T successor = key(successorNode, 0);
                node.keys[index] = successor;
                delete(right, successor);
            } else {
                mergeChildren(node, index);
                delete(left, item);
            }
            return;
        }

        Node child = node.children[index];
        if (child.count == degree - 1) {
            if (index > 0 && node.children[index - 1].count >= degree) {
                borrowFromLeft(node, index);
            } else if (index < node.count
                    && node.children[index + 1].count >= degree) {
                borrowFromRight(node, index);
            } else if (index < node.count) {
                mergeChildren(node, index);
            } else {
                mergeChildren(node, index - 1);
                index--;
            }
        }
        delete(node.children[index], item);
    }

    private void borrowFromLeft(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index - 1];

        for (int j = child.count - 1; j >= 0; j--) {
            child.keys[j + 1] = child.keys[j];
        }
        if (!child.leaf) {
            for (int j = child.count; j >= 0; j--) {
                child.children[j + 1] = child.children[j];
            }
            child.children[0] = sibling.children[sibling.count];
            sibling.children[sibling.count] = null;
        }

        child.keys[0] = parent.keys[index - 1];
        parent.keys[index - 1] = sibling.keys[sibling.count - 1];
        sibling.keys[sibling.count - 1] = null;
        sibling.count--;
        child.count++;
    }

    private void borrowFromRight(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index + 1];

        child.keys[child.count] = parent.keys[index];
        if (!child.leaf) {
            child.children[child.count + 1] = sibling.children[0];
        }
        parent.keys[index] = sibling.keys[0];

        for (int j = 1; j < sibling.count; j++) {
            sibling.keys[j - 1] = sibling.keys[j];
        }
        sibling.keys[sibling.count - 1] = null;

        if (!sibling.leaf) {
            for (int j = 1; j <= sibling.count; j++) {
                sibling.children[j - 1] = sibling.children[j];
            }
            sibling.children[sibling.count] = null;
        }

        sibling.count--;
        child.count++;
    }

    private void mergeChildren(Node parent, int index) {
        Node left = parent.children[index];
        Node right = parent.children[index + 1];
        int leftCount = left.count;

        left.keys[leftCount] = parent.keys[index];
        for (int j = 0; j < right.count; j++) {
            left.keys[leftCount + 1 + j] = right.keys[j];
        }
        if (!left.leaf) {
            for (int j = 0; j <= right.count; j++) {
                left.children[leftCount + 1 + j] = right.children[j];
            }
        }
        left.count = leftCount + 1 + right.count;

        for (int j = index; j < parent.count - 1; j++) {
            parent.keys[j] = parent.keys[j + 1];
        }
        parent.keys[parent.count - 1] = null;

        for (int j = index + 1; j < parent.count; j++) {
            parent.children[j] = parent.children[j + 1];
        }
        parent.children[parent.count] = null;
        parent.count--;
    }

    private int copyInOrder(Node node, Object[] result, int position) {
        for (int i = 0; i < node.count; i++) {
            if (!node.leaf) {
                position = copyInOrder(node.children[i], result, position);
            }
            result[position++] = node.keys[i];
        }
        if (!node.leaf) {
            position = copyInOrder(node.children[node.count], result, position);
        }
        return position;
    }
}
