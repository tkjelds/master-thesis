import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

interface Order<K> {
    int compare(K left, K right);
}

@Entry.ThreadSafe
class Entry<K, V> {
    @interface ThreadSafe {}

    private final K key;
    private final V value;

    public Entry(K key, V value) {
        if (key == null || value == null) throw new NullPointerException();
        this.key = key;
        this.value = value;
    }

    public K key() {
        return key;
    }

    public V value() {
        return value;
    }
}

@Snapshot.ThreadSafe
class Snapshot<K, V> {
    @interface ThreadSafe {}

    private final TreeDictionary<K, V> tree;

    public Snapshot(TreeDictionary<K, V> tree) {
        this.tree = tree;
        tree.lock.lock();
        try {
            // Copy the state here if needed
        } finally {
            tree.lock.unlock();
        }
    }

    public int size() {
        return tree.size;
    }

    public V get(K key) {
        return tree.get(key);
    }

    public Entry<K, V> entryAt(int index) {
        return tree.entryAt(index);
    }
}

@TreeDictionary.ThreadSafe
class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private final Order<K> order;
    private Node root = null;
    private int size = 0;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    public TreeDictionary(Order<K> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException();
        lock.lock();
        try {
            Node node = root;
            Node parent = null;
            int cmp = 0;
            while (node != null) {
                parent = node;
                cmp = order.compare(key, node.key);
                if (cmp < 0) {
                    node = node.left;
                } else if (cmp > 0) {
                    node = node.right;
                } else {
                    V oldValue = node.value;
                    node.value = value;
                    return oldValue;
                }
            }
            Node newNode = new Node(key, value);
            if (parent == null) {
                root = newNode;
            } else if (cmp < 0) {
                parent.left = newNode;
            } else {
                parent.right = newNode;
            }
            newNode.parent = parent;
            fixAfterInsertion(newNode);
            size++;
            return null;
        } finally {
            lock.unlock();
        }
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException();
        lock.lock();
        try {
            Node node = findNode(key);
            return (node != null) ? node.value : null;
        } finally {
            lock.unlock();
        }
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException();
        lock.lock();
        try {
            Node node = findNode(key);
            if (node == null) {
                return null;
            }
            V oldValue = node.value;
            deleteNode(node);
            size--;
            return oldValue;
        } finally {
            lock.unlock();
        }
    }

    public boolean containsKey(K key) {
        if (key == null) throw new NullPointerException();
        lock.lock();
        try {
            return findNode(key) != null;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return size;
    }

    public Entry<K, V> entryAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        lock.lock();
        try {
            Node node = treeAt(index);
            return new Entry<>(node.key, node.value);
        } finally {
            lock.unlock();
        }
    }

    public int rank(K key) {
        if (key == null) throw new NullPointerException();
        lock.lock();
        try {
            int rank = 0;
            Node node = root;
            while (node != null) {
                int cmp = order.compare(key, node.key);
                if (cmp < 0) {
                    node = node.left;
                } else {
                    rank += size(node.left);
                    if (cmp > 0) {
                        rank++;
                    }
                    node = node.right;
                }
            }
            return rank;
        } finally {
            lock.unlock();
        }
    }

    public Snapshot<K, V> snapshot() {
        return new Snapshot<>(this);
    }

    private Node findNode(K key) {
        Node node = root;
        while (node != null) {
            int cmp = order.compare(key, node.key);
            if (cmp < 0) {
                node = node.left;
            } else if (cmp > 0) {
                node = node.right;
            } else {
                return node;
            }
        }
        return null;
    }

    private Node treeAt(int index) {
        int count = 0;
        Node node = root;
        while (true) {
            if (node.left != null) {
                int leftSize = size(node.left);
                if (count + leftSize <= index) {
                    count += leftSize;
                    node = node.right;
                } else {
                    node = node.left;
                }
            } else {
                if (count == index) {
                    return node;
                } else {
                    count++;
                    node = node.right;
                }
            }
        }
    }

    private int size(Node node) {
        if (node == null) return 0;
        return node.getCount() + size(node.left) + size(node.right);
    }

    private void fixAfterInsertion(Node node) {
        while (node != root && colorOf(node.parent) == RED) {
            Node parent = node.parent;
            Node grandparent = parent.getParent();
            if (parent == grandparent.left) {
                Node uncle = grandparent.right;
                if (colorOf(uncle) == RED) {
                    setColor(grandparent, RED);
                    setColor(parent, BLACK);
                    setColor(uncle, BLACK);
                    node = grandparent;
                } else {
                    if (node == parent.right) { // Case LL
                        node = parent;
                        rotateLeft(node);
                    }
                    setColor(parent, BLACK); // Case LR
                    setColor(grandparent, RED); // Case LR
                    rotateRight(grandparent);
                }
            } else {
                Node uncle = grandparent.left;
                if (colorOf(uncle) == RED) {
                    setColor(grandparent, RED);
                    setColor(parent, BLACK);
                    setColor(uncle, BLACK);
                    node = grandparent;
                } else {
                    if (node == parent.left) { // Case RR
                        node = parent;
                        rotateRight(node);
                    }
                    setColor(parent, BLACK); // Case RL
                    setColor(grandparent, RED); // Case RL
                    rotateLeft(grandparent);
                }
            }
        }
        setColor(root, BLACK);
    }

    private void deleteNode(Node node) {
        Node replacement = null;
        if (node.left != null && node.right != null) {
            replacement = min(node.right);
            transferColors(replacement, node); // x and y must have the same color
            replacement = replacement.right;
        } else {
            replacement = node.left != null ? node.left : node.right;
        }

        if (replacement != null) {
            replacement.parent = node.getParent();
            setColor(replacement, BLACK);
            Node parent = node.getParent();
            if (parent == null) {
                root = replacement;
            } else if (node == parent.left) {
                parent.left = replacement;
            } else {
                parent.right = replacement;
            }
        } else {
            Node parent = node.getParent();
            setColor(node, BLACK);
            if (parent == null) {
                root = null;
            } else {
                deleteCase1(parent);
            }
        }
    }

    private void deleteCase1(Node node) {
        if (node.getParent() == null) root = null;
    }

    private void deleteCase2(Node node) {
        Node sibling = getSibling(node);
        if (colorOf(sibling) == RED) {
            setColor(parentOf(node), RED);
            setColor(sibling, BLACK);
            if (node == leftOf(parentOf(node))) {
                rotateLeft(parentOf(node));
            } else {
                rotateRight(parentOf(node));
            }
        }
        deleteCase3(node);
    }

    private void deleteCase3(Node node) {
        Node sibling = getSibling(node);
        if (colorOf(parentOf(node)) == BLACK &&
            colorOf(sibling) == BLACK &&
            colorOf(sibling.left) == BLACK &&
            colorOf(sibling.right) == BLACK) {
            setColor(sibling, RED);
            deleteCase1(parentOf(node));
        } else {
            deleteCase4(node);
        }
    }

    private void deleteCase4(Node node) {
        Node sibling = getSibling(node);
        if (colorOf(parentOf(node)) == RED &&
            colorOf(sibling) == BLACK &&
            colorOf(sibling.left) == BLACK &&
            colorOf(sibling.right) == BLACK) {
            setColor(sibling, RED);
            setColor(parentOf(node), BLACK);
        } else {
            deleteCase5(node);
        }
    }

    private void deleteCase5(Node node) {
        Node sibling = getSibling(node);
        if (colorOf(sibling) == BLACK) {
            if (node == leftOf(parentOf(node)) &&
                colorOf(sibling.right) == BLACK &&
                colorOf(sibling.left) == RED) {
                setColor(sibling, RED);
                setColor(sibling.left, BLACK);
                rotateRight(sibling);
            } else if (node == rightOf(parentOf(node)) &&
                       colorOf(sibling.left) == BLACK &&
                       colorOf(sibling.right) == RED) {
                setColor(sibling, RED);
                setColor(sibling.right, BLACK);
                rotateLeft(sibling);
            }
        }
        deleteCase6(node);
    }

    private void deleteCase6(Node node) {
        Node sibling = getSibling(node);
        setColor(sibling, colorOf(parentOf(node)));
        setColor(parentOf(node), BLACK);
        if (node == leftOf(parentOf(node))) {
            setColor(sibling.right, BLACK);
            rotateLeft(parentOf(node));
        } else {
            setColor(sibling.left, BLACK);
            rotateRight(parentOf(node));
        }
    }

    private Node min(Node node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node max(Node node) {
        while (node.right != null) {
            node = node.right;
        }
        return node;
    }

    private Node getSibling(Node node) {
        if (node == leftOf(parentOf(node))) {
            return rightOf(parentOf(node));
        } else {
            return leftOf(parentOf(node));
        }
    }

    private Node rotateLeft(Node node) {
        Node right = node.right;
        node.right = right.left;
        if (right.left != null) {
            right.left.parent = node;
        }
        right.parent = parentOf(node);
        if (parentOf(node) == null) {
            root = right;
        } else if (node == parentOf(node).left) {
            leftOf(parentOf(node)) = right;
        } else {
            rightOf(parentOf(node)) = right;
        }
        right.left = node;
        node.parent = right;
        setCount(right, getCount(node) + getCount(node.right) + 1);
        return right;
    }

    private Node rotateRight(Node node) {
        Node left = node.left;
        node.left = rightOf(left);
        if (left.right != null) {
            left.right.parent = node;
        }
        left.parent = parentOf(node);
        if (parentOf(node) == null) {
            root = left;
        } else if (node == rightOf(parentOf(node))) {
            rightOf(parentOf(node)) = left;
        } else {
            leftOf(parentOf(node)) = left;
        }
        left.right = node;
        node.parent = left;
        setCount(left, getCount(node) + getCount(node.left) + 1);
        return left;
    }

    private Node parentOf(Node node) {
        return (node == null ? null : node.parent);
    }

    private Node leftOf(Node node) {
        return (node == null ? null : node.left);
    }

    private Node rightOf(Node node) {
        return (node == null ? null : node.right);
    }

    private int getCount(Node node) {
        return (node == null ? 0 : node.getCount());
    }

    private void setColor(Node node, boolean color) {
        if (node != null) node.setColor(color);
    }

    private boolean colorOf(Node node) {
        return (node == null ? BLACK : node.isRed());
    }

    private void updateCount(Node node) {
        int count = 1;
        if (node.left != null) count += getCount(node.left);
        if (node.right != null) count += getCount(node.right);
        setCount(node, count);
    }

    private void setCount(Node node, int count) {
        if (node != null) node.setCount(count);
    }

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    class Node {
        K key;
        V value;
        Node left, right, parent;
        boolean color;

        public Node(K key, V value) {
            this.key = key;
            this.value = value;
            left = null;
            right = null;
            parent = null;
            color = RED;
        }

        public void setColor(boolean color) {
            this.color = color;
        }

        public boolean isRed() {
            return color == RED;
        }

        public int getCount() {
            return getCountHelper();
        }

        public void setCount(int count) {
            this.count = count;
        }
    }
}
