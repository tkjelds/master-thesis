@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node<E> {
        final Object[] keys;
        final Node<E>[] children;
        final boolean leaf;
        int count;

        @SuppressWarnings("unchecked")
        Node(int keyCapacity, int childCapacity, boolean leaf) {
            this.keys = new Object[keyCapacity];
            this.children = (Node<E>[]) new Node<?>[childCapacity];
            this.leaf = leaf;
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private final int minimumDegree;
    private final int maximumKeys;
    private final int maximumChildren;

    private Node<T> root;
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2) {
            throw new IllegalArgumentException("minimumDegree must be at least 2");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }

        long childCapacity = 2L * minimumDegree;
        long keyCapacity = childCapacity - 1L;
        if (childCapacity > Integer.MAX_VALUE || keyCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("node-array capacity overflows int");
        }

        this.minimumDegree = minimumDegree;
        this.maximumKeys = (int) keyCapacity;
        this.maximumChildren = (int) childCapacity;
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (find(item) != null) {
                return false;
            }

            if (root == null) {
                root = newNode(true);
                root.keys[0] = item;
                root.count = 1;
                size++;
                return true;
            }

            if (root.count == maximumKeys) {
                Node<T> newRoot = newNode(false);
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
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (find(item) == null) {
                return false;
            }

            deleteFromNode(root, item);
            if (root.count == 0) {
                root = root.leaf ? null : root.children[0];
            }
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            return find(item) != null;
        }
    }

    @SuppressWarnings("unchecked")
    public T first() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }

            Node<T> node = root;
            while (!node.leaf) {
                node = node.children[0];
            }
            return (T) node.keys[0];
        }
    }

    @SuppressWarnings("unchecked")
    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }

            Node<T> node = root;
            while (!node.leaf) {
                node = node.children[node.count];
            }
            return (T) node.keys[node.count - 1];
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[size];
            if (root != null) {
                copyInOrder(root, result, 0);
            }
            return result;
        }
    }

    private Node<T> newNode(boolean leaf) {
        return new Node<T>(maximumKeys, maximumChildren, leaf);
    }

    private Node<T> find(T item) {
        Node<T> node = root;
        while (node != null) {
            int index = 0;
            while (index < node.count) {
                int comparison = order.compare(item, keyAt(node, index));
                if (comparison <= 0) {
                    break;
                }
                index++;
            }

            if (index < node.count
                    && order.compare(item, keyAt(node, index)) == 0) {
                return node;
            }
            if (node.leaf) {
                return null;
            }
            node = node.children[index];
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node<T> node, int index) {
        return (T) node.keys[index];
    }

    private void insertNonFull(Node<T> node, T item) {
        int index = node.count - 1;

        if (node.leaf) {
            while (index >= 0 && order.compare(item, keyAt(node, index)) < 0) {
                node.keys[index + 1] = node.keys[index];
                index--;
            }
            node.keys[index + 1] = item;
            node.count++;
            return;
        }

        while (index >= 0 && order.compare(item, keyAt(node, index)) < 0) {
            index--;
        }
        index++;

        if (node.children[index].count == maximumKeys) {
            splitChild(node, index);
            if (order.compare(item, keyAt(node, index)) > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node<T> parent, int index) {
        Node<T> fullChild = parent.children[index];
        Node<T> newChild = newNode(fullChild.leaf);
        newChild.count = minimumDegree - 1;

        for (int i = 0; i < minimumDegree - 1; i++) {
            newChild.keys[i] = fullChild.keys[i + minimumDegree];
            fullChild.keys[i + minimumDegree] = null;
        }

        if (!fullChild.leaf) {
            for (int i = 0; i < minimumDegree; i++) {
                newChild.children[i] = fullChild.children[i + minimumDegree];
                fullChild.children[i + minimumDegree] = null;
            }
        }

        Object median = fullChild.keys[minimumDegree - 1];
        fullChild.keys[minimumDegree - 1] = null;
        fullChild.count = minimumDegree - 1;

        for (int i = parent.count; i >= index + 1; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[index + 1] = newChild;

        for (int i = parent.count - 1; i >= index; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }
        parent.keys[index] = median;
        parent.count++;
    }

    private void deleteFromNode(Node<T> node, T item) {
        int index = 0;
        while (index < node.count
                && order.compare(item, keyAt(node, index)) > 0) {
            index++;
        }

        if (index < node.count
                && order.compare(item, keyAt(node, index)) == 0) {
            if (node.leaf) {
                removeKeyAt(node, index);
            } else if (node.children[index].count >= minimumDegree) {
                T predecessor = maximumKey(node.children[index]);
                node.keys[index] = predecessor;
                deleteFromNode(node.children[index], predecessor);
            } else if (node.children[index + 1].count >= minimumDegree) {
                T successor = minimumKey(node.children[index + 1]);
                node.keys[index] = successor;
                deleteFromNode(node.children[index + 1], successor);
            } else {
                mergeChildren(node, index);
                deleteFromNode(node.children[index], item);
            }
            return;
        }

        if (node.leaf) {
            return;
        }

        if (node.children[index].count == minimumDegree - 1) {
            if (index > 0
                    && node.children[index - 1].count >= minimumDegree) {
                borrowFromPrevious(node, index);
            } else if (index < node.count
                    && node.children[index + 1].count >= minimumDegree) {
                borrowFromNext(node, index);
            } else if (index < node.count) {
                mergeChildren(node, index);
            } else {
                mergeChildren(node, index - 1);
                index--;
            }
        }

        deleteFromNode(node.children[index], item);
    }

    private void removeKeyAt(Node<T> node, int index) {
        for (int i = index; i < node.count - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keys[node.count - 1] = null;
        node.count--;
    }

    private void borrowFromPrevious(Node<T> parent, int index) {
        Node<T> child = parent.children[index];
        Node<T> sibling = parent.children[index - 1];

        for (int i = child.count - 1; i >= 0; i--) {
            child.keys[i + 1] = child.keys[i];
        }
        if (!child.leaf) {
            for (int i = child.count; i >= 0; i--) {
                child.children[i + 1] = child.children[i];
            }
            child.children[0] = sibling.children[sibling.count];
            sibling.children[sibling.count] = null;
        }

        child.keys[0] = parent.keys[index - 1];
        parent.keys[index - 1] = sibling.keys[sibling.count - 1];
        sibling.keys[sibling.count - 1] = null;

        child.count++;
        sibling.count--;
    }

    private void borrowFromNext(Node<T> parent, int index) {
        Node<T> child = parent.children[index];
        Node<T> sibling = parent.children[index + 1];

        child.keys[child.count] = parent.keys[index];
        if (!child.leaf) {
            child.children[child.count + 1] = sibling.children[0];
        }
        parent.keys[index] = sibling.keys[0];

        for (int i = 0; i < sibling.count - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[sibling.count - 1] = null;

        if (!sibling.leaf) {
            for (int i = 0; i < sibling.count; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.children[sibling.count] = null;
        }

        child.count++;
        sibling.count--;
    }

    private void mergeChildren(Node<T> parent, int index) {
        Node<T> left = parent.children[index];
        Node<T> right = parent.children[index + 1];
        int leftCount = left.count;
        int rightCount = right.count;

        left.keys[leftCount] = parent.keys[index];
        for (int i = 0; i < rightCount; i++) {
            left.keys[leftCount + 1 + i] = right.keys[i];
            right.keys[i] = null;
        }

        if (!left.leaf) {
            for (int i = 0; i <= rightCount; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
                right.children[i] = null;
            }
        }

        left.count = leftCount + rightCount + 1;

        for (int i = index; i < parent.count - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[parent.count - 1] = null;

        for (int i = index + 1; i < parent.count; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[parent.count] = null;
        parent.count--;
    }

    private T minimumKey(Node<T> node) {
        while (!node.leaf) {
            node = node.children[0];
        }
        return keyAt(node, 0);
    }

    private T maximumKey(Node<T> node) {
        while (!node.leaf) {
            node = node.children[node.count];
        }
        return keyAt(node, node.count - 1);
    }

    private int copyInOrder(Node<T> node, Object[] destination, int position) {
        for (int i = 0; i < node.count; i++) {
            if (!node.leaf) {
                position = copyInOrder(node.children[i], destination, position);
            }
            destination[position++] = node.keys[i];
        }
        if (!node.leaf) {
            position = copyInOrder(node.children[node.count], destination, position);
        }
        return position;
    }
}
