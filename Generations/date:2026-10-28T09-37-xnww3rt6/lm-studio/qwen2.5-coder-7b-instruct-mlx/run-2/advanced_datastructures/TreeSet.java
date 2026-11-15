import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
class TreeSet<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private Node root = null;
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("Order cannot be null");
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lock();
        try {
            Node newNode = new Node(item);
            if (root == null) {
                root = newNode;
            } else {
                Node current = root;
                while (true) {
                    int cmp = order.compare(item, current.item);
                    if (cmp == 0) {
                        return false;
                    } else if (cmp < 0) {
                        if (current.left == null) {
                            current.left = newNode;
                            newNode.parent = current;
                            updateBlackHeight(current);
                            break;
                        }
                        current = current.left;
                    } else {
                        if (current.right == null) {
                            current.right = newNode;
                            newNode.parent = current;
                            updateBlackHeight(current);
                            break;
                        }
                        current = current.right;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lock();
        try {
            Node node = findNode(item);
            if (node == null) {
                return false;
            }
            Node replacement = node.left != null ? node.left : node.right;
            if (replacement == null) {
                Node parent = node.parent;
                if (parent == null) {
                    root = null;
                } else {
                    updateBlackHeight(parent);
                    if (parent.left == node) {
                        parent.left = null;
                    } else {
                        parent.right = null;
                    }
                }
            } else {
                Node rightChild = replacement.right;
                if (replacement.parent == node) {
                    rightChild.parent = parent;
                    if (parent != null) {
                        updateBlackHeight(parent);
                        if (parent.left == node) {
                            parent.left = rightChild;
                        } else {
                            parent.right = rightChild;
                        }
                    } else {
                        root = rightChild;
                    }
                } else {
                    replacement.parent.left = null;
                    updateBlackHeight(replacement);
                    if (replacement.right != null) {
                        replacement.right.parent = replacement;
                    }
                    replacement.parent = parent;
                    replacement.right = node.right;
                    if (node.right != null) {
                        node.right.parent = replacement;
                    }
                    if (parent != null) {
                        updateBlackHeight(parent);
                        if (parent.left == node) {
                            parent.left = replacement;
                        } else {
                            parent.right = replacement;
                        }
                    } else {
                        root = replacement;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lock();
        try {
            return findNode(item) != null;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return root != null ? root.size : 0;
    }

    public T get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index out of bounds");
        }
        lock.lock();
        try {
            Node node = findNodeByIndex(index);
            return node.item;
        } finally {
            lock.unlock();
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lock();
        try {
            int rank = 0;
            Node current = root;
            while (current != null) {
                int cmp = order.compare(item, current.item);
                if (cmp == 0) {
                    rank += sizeOf(current.left);
                    break;
                } else if (cmp < 0) {
                    current = current.left;
                } else {
                    rank += sizeOf(current.left) + 1;
                    current = current.right;
                }
            }
            return rank;
        } finally {
            lock.unlock();
        }
    }

    public Snapshot<T> snapshot() {
        lock.lock();
        try {
            return new Snapshot<>(root);
        } finally {
            lock.unlock();
        }
    }

    private Node findNode(T item) {
        Node current = root;
        while (current != null) {
            int cmp = order.compare(item, current.item);
            if (cmp == 0) {
                return current;
            } else if (cmp < 0) {
                current = current.left;
            } else {
                current = current.right;
            }
        }
        return null;
    }

    private Node findNodeByIndex(int index) {
        Node current = root;
        while (current != null) {
            int leftSize = sizeOf(current.left);
            if (index == leftSize) {
                return current;
            } else if (index < leftSize) {
                current = current.left;
            } else {
                index -= leftSize + 1;
                current = current.right;
            }
        }
        throw new IndexOutOfBoundsException("Index out of bounds");
    }

    private int sizeOf(Node node) {
        return node != null ? node.size : 0;
    }

    private void updateBlackHeight(Node node) {
        Node current = node.parent;
        while (current != null) {
            updateBlackHeight(current);
            if (isRed(node)) {
                if (isRed(siblingOf(current))) {
                    rightRotate(current.parent);
                    current.left.black = true;
                    current.right.black = true;
                    current.black = false;
                    node = current.parent;
                } else {
                    if (current.left == node) {
                        rightRotate(current);
                        current = node;
                        leftRotate(current.parent);
                    } else {
                        leftRotate(current);
                        rightRotate(current.parent);
                        current.right.black = true;
                    }
                    current.color = null; // Reset color
                }
            } else {
                break;
            }
        }
        root.black = true;
    }

    private Node siblingOf(Node node) {
        return node.parent.left == node ? node.parent.right : node.parent.left;
    }

    private Node leftRotate(Node node) {
        Node rightChild = node.right;
        node.right = rightChild.left;
        if (rightChild.left != null) {
            rightChild.left.parent = node;
        }
        rightChild.parent = node.parent;
        if (node.parent == null) {
            root = rightChild;
        } else if (node == node.parent.left) {
            node.parent.left = rightChild;
        } else {
            node.parent.right = rightChild;
        }
        rightChild.left = node;
        node.parent = rightChild;
        updateSize(node);
        updateSize(rightChild);
        return rightChild;
    }

    private Node rightRotate(Node node) {
        Node leftChild = node.left;
        node.left = leftChild.right;
        if (leftChild.right != null) {
            leftChild.right.parent = node;
        }
        leftChild.parent = node.parent;
        if (node.parent == null) {
            root = leftChild;
        } else if (node == node.parent.right) {
            node.parent.right = leftChild;
        } else {
            node.parent.left = leftChild;
        }
        leftChild.right = node;
        node.parent = leftChild;
        updateSize(node);
        updateSize(leftChild);
        return leftChild;
    }

    private boolean isRed(Node node) {
        return node != null && !node.black;
    }

    private void updateSize(Node node) {
        if (node != null) {
            node.size = 1 + sizeOf(node.left) + sizeOf(node.right);
        }
    }

    class Node {
        T item;
        boolean black = true; // Default color is red
        int size = 1;
        Node left, right, parent;

        Node(T item) {
            this.item = item;
        }
    }

    class Snapshot<T> {
        private final Node root;

        Snapshot(Node root) {
            this.root = copy(root);
        }

        private Node copy(Node node) {
            if (node == null) return null;
            Node newNode = new Node(node.item);
            newNode.left = copy(node.left);
            if (newNode.left != null) {
                newNode.left.parent = newNode;
            }
            newNode.right = copy(node.right);
            if (newNode.right != null) {
                newNode.right.parent = newNode;
            }
            newNode.size = node.size;
            newNode.black = node.black;
            return newNode;
        }

        public int size() {
            return root != null ? root.size : 0;
        }

        public T get(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException("Index out of bounds");
            }
            return findNodeByIndex(index, root);
        }

        public boolean contains(T item) {
            if (item == null) {
                throw new NullPointerException("Item cannot be null");
            }
            return findNode(item, root) != null;
        }

        private Node findNode(T item, Node current) {
            while (current != null) {
                int cmp = order.compare(item, current.item);
                if (cmp == 0) {
                    return current;
                } else if (cmp < 0) {
                    current = current.left;
                } else {
                    current = current.right;
                }
            }
            return null;
        }

        private T findNodeByIndex(int index, Node current) {
            if (current == null) return null;
            int leftSize = sizeOf(current.left);
            if (index == leftSize) {
                return current.item;
            } else if (index < leftSize) {
                return findNodeByIndex(index, current.left);
            } else {
                return findNodeByIndex(index - leftSize - 1, current.right);
            }
        }

        private int sizeOf(Node node) {
            return node != null ? node.size : 0;
        }
    }

    public static void main(String[] args) {
        Order<Integer> order = (a, b) -> Integer.compare(a, b);
        TreeSet<Integer> set = new TreeSet<>(order);
        set.add(3);
        set.add(1);
        set.add(2);
        System.out.println(set.get(0)); // 1
        System.out.println(set.rank(2)); // 1
        Snapshot<Integer> snap = set.snapshot();
        System.out.println(snap.contains(3)); // true
        set.remove(2);
        System.out.println(set.get(0)); // 1
        System.out.println(snap.contains(2)); // false
    }
}
