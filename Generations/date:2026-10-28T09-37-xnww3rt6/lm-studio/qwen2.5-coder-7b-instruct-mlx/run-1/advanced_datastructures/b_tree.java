@Order.ThreadSafe
class Order<T> {
    @interface ThreadSafe {}

    public abstract int compare(T left, T right);
}

@BTree.ThreadSafe
class BTree<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private Node root;
    private int minimumDegree;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || minimumDegree >= Integer.MAX_VALUE / 4) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        this.minimumDegree = minimumDegree;
        this.order = order;
        root = new Node(true);
    }

    public boolean add(T item) {
        synchronized (this) {
            if (item == null) {
                throw new NullPointerException("Null item");
            }
            if (root.isLeaf() && root.getKeys().length == 0) {
                Node newNode = new Node(true);
                newNode.addKey(item);
                root = newNode;
                return true;
            }
            boolean result = insert(root, item);
            if (root.getKeys().length == 2 * minimumDegree) {
                Node newRoot = new Node(false);
                newRoot.addChild(root);
                splitChild(newRoot, 0);
                root = newRoot;
            }
            return result;
        }
    }

    private boolean insert(Node node, T item) {
        int index = findInsertIndex(node.getKeys(), item);
        if (node.isLeaf()) {
            node.addKey(item);
            return true;
        } else {
            Node child = node.getChild(index);
            boolean result = insert(child, item);
            if (child.getKeys().length == 2 * minimumDegree) {
                splitChild(node, index);
                if (order.compare(item, child.getKeys()[child.getKeys().length - 1]) > 0) {
                    index++;
                }
            }
            return result;
        }
    }

    private int findInsertIndex(T[] keys, T item) {
        for (int i = 0; i < keys.length; i++) {
            if (order.compare(keys[i], item) > 0) {
                return i;
            }
        }
        return keys.length;
    }

    private void splitChild(Node parent, int index) {
        Node child = parent.getChild(index);
        Node sibling = new Node(child.isLeaf());
        for (int i = minimumDegree; i < 2 * minimumDegree; i++) {
            sibling.addKey(child.getKey(i));
        }
        if (!child.isLeaf()) {
            for (int i = minimumDegree; i < 2 * minimumDegree; i++) {
                sibling.addChild(child.getChild(i));
            }
        }
        child = child.getPrevSibling();
        if (child == null) {
            parent.setChild(0, sibling);
        } else {
            while (child.getNextSibling() != parent.getChild(index)) {
                child = child.getNextSibling();
            }
            child.setNextSibling(sibling);
        }
        sibling.setPrevSibling(child);
        parent.addKey(child.getKey(minimumDegree - 1));
        parent.removeChild(index + 1);
    }

    public boolean remove(T item) {
        synchronized (this) {
            if (item == null) {
                throw new NullPointerException("Null item");
            }
            boolean result = delete(root, item);
            if (root.getKeys().length == 0) {
                root = root.getChild(0);
            }
            return result;
        }
    }

    private boolean delete(Node node, T item) {
        int index = findInsertIndex(node.getKeys(), item);
        if (node.isLeaf()) {
            for (int i = 0; i < node.getKeys().length; i++) {
                if (order.compare(node.getKey(i), item) == 0) {
                    node.removeKey(i);
                    return true;
                }
            }
            return false;
        } else {
            Node child = node.getChild(index);
            boolean result = delete(child, item);
            if (child.getKeys().length < minimumDegree) {
                Node prevSibling = child.getPrevSibling();
                Node nextSibling = child.getNextSibling();
                if (prevSibling != null && prevSibling.getKeys().length > minimumDegree) {
                    borrowFromPrev(child, prevSibling);
                } else if (nextSibling != null && nextSibling.getKeys().length > minimumDegree) {
                    borrowFromNext(child, nextSibling);
                } else if (prevSibling != null) {
                    mergeWithPrev(child, prevSibling);
                } else {
                    mergeWithNext(child, nextSibling);
                }
            }
            return result;
        }
    }

    private void borrowFromPrev(Node child, Node prevSibling) {
        T borrowedKey = prevSibling.removeKey(prevSibling.getKeys().length - 1);
        child.addKey(borrowedKey);
        Node borrowedChild = prevSibling.removeChild(prevSibling.getChildren().size() - 1);
        if (borrowedChild != null) {
            child.addChild(borrowedChild);
        }
    }

    private void borrowFromNext(Node child, Node nextSibling) {
        T borrowedKey = nextSibling.removeKey(0);
        child.addKey(borrowedKey);
        Node borrowedChild = nextSibling.removeChild(0);
        if (borrowedChild != null) {
            child.addChild(borrowedChild);
        }
    }

    private void mergeWithPrev(Node child, Node prevSibling) {
        T mergedKey = child.removeKey(0);
        for (int i = 0; i < prevSibling.getKeys().length; i++) {
            child.addKey(prevSibling.getKey(i));
        }
        if (!child.isLeaf()) {
            for (int i = 0; i < prevSibling.getChildren().size(); i++) {
                child.addChild(prevSibling.getChild(i));
            }
        }
        Node nextSibling = child.getNextSibling();
        prevSibling.setNextSibling(nextSibling);
        if (nextSibling != null) {
            nextSibling.setPrevSibling(prevSibling);
        }
        parent.removeKey(findInsertIndex(parent.getKeys(), mergedKey));
    }

    private void mergeWithNext(Node child, Node nextSibling) {
        T mergedKey = parent.getKey(findInsertIndex(parent.getKeys(), child.getKey(0)));
        for (int i = 0; i < nextSibling.getKeys().length; i++) {
            child.addKey(nextSibling.getKey(i));
        }
        if (!child.isLeaf()) {
            for (int i = 0; i < nextSibling.getChildren().size(); i++) {
                child.addChild(nextSibling.getChild(i));
            }
        }
        parent.removeKey(findInsertIndex(parent.getKeys(), mergedKey));
    }

    public boolean contains(T item) {
        synchronized (this) {
            if (item == null) {
                throw new NullPointerException("Null item");
            }
            return search(root, item);
        }
    }

    private boolean search(Node node, T item) {
        int index = findInsertIndex(node.getKeys(), item);
        if (node.isLeaf()) {
            for (int i = 0; i < node.getKeys().length; i++) {
                if (order.compare(node.getKey(i), item) == 0) {
                    return true;
                }
            }
            return false;
        } else {
            Node child = node.getChild(index);
            if (order.compare(child.getKey(child.getKeys().length - 1), item) < 0) {
                child = node.getChild(index + 1);
            }
            return search(child, item);
        }
    }

    public T first() {
        synchronized (this) {
            if (root == null || root.getKeys().length == 0) {
                throw new IllegalStateException("Empty tree");
            }
            Node current = root;
            while (!current.isLeaf()) {
                current = current.getChild(0);
            }
            return current.getKey(0);
        }
    }

    public T last() {
        synchronized (this) {
            if (root == null || root.getKeys().length == 0) {
                throw new IllegalStateException("Empty tree");
            }
            Node current = root;
            while (!current.isLeaf()) {
                current = current.getChild(current.getChildren().size() - 1);
            }
            return current.getKey(current.getKeys().length - 1);
        }
    }

    public int size() {
        synchronized (this) {
            if (root == null) {
                return 0;
            }
            return countKeys(root);
        }
    }

    private int countKeys(Node node) {
        if (node.isLeaf()) {
            return node.getKeys().length;
        } else {
            int count = 0;
            for (Node child : node.getChildren()) {
                count += countKeys(child);
            }
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            if (root == null) {
                return new Object[0];
            }
            List<Object> keys = new ArrayList<>();
            traverse(root, keys);
            return keys.toArray();
        }
    }

    private void traverse(Node node, List<Object> keys) {
        if (node.isLeaf()) {
            for (T key : node.getKeys()) {
                keys.add(key);
            }
        } else {
            for (int i = 0; i < node.getKeys().length; i++) {
                traverse(node.getChild(i), keys);
                keys.add(node.getKey(i));
            }
        }
    }

    private static class Node {
        private final boolean isLeaf;
        private T[] keys;
        private List<Node> children;
        private Node prevSibling;
        private Node nextSibling;

        public Node(boolean isLeaf) {
            this.isLeaf = isLeaf;
            this.keys = (T[]) new Comparable[2 * minimumDegree - 1];
            this.children = new ArrayList<>();
        }

        public boolean isLeaf() {
            return isLeaf;
        }

        public T getKey(int index) {
            return keys[index];
        }

        public void addKey(T key) {
            int i = keys.length - 1;
            while (i >= 0 && order.compare(keys[i], key) > 0) {
                keys[i + 1] = keys[i];
                i--;
            }
            keys[i + 1] = key;
        }

        public void removeKey(int index) {
            for (int i = index; i < keys.length - 1; i++) {
                keys[i] = keys[i + 1];
            }
        }

        public Node getChild(int index) {
            return children.get(index);
        }

        public void addChild(Node child) {
            children.add(child);
        }

        public Node getPrevSibling() {
            return prevSibling;
        }

        public void setPrevSibling(Node prevSibling) {
            this.prevSibling = prevSibling;
        }

        public Node getNextSibling() {
            return nextSibling;
        }

        public void setNextSibling(Node nextSibling) {
            this.nextSibling = nextSibling;
        }

        public int getChildrenSize() {
            return children.size();
        }

        public List<Node> getChildren() {
            return children;
        }

        public void removeChild(int index) {
            children.remove(index);
        }
    }
}
