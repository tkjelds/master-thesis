import java.io.Serializable;

interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
class TreeSet<T> implements Serializable {
    @interface ThreadSafe {}

    private static final long serialVersionUID = 1L;
    private Node<T> root;
    private Order<T> order;

    public TreeSet(Order<T> order) {
        this.order = order;
    }

    private static class Node<T> implements Serializable {
        T item;
        int size = 1;
        boolean color; // true for black, false for red
        Node<T> left, right, parent;

        Node(T item, boolean color) {
            this.item = item;
            this.color = color;
        }

        Node<T> sibling() {
            return parent == null ? null : (isLeftChild() ? parent.right : parent.left);
        }

        boolean isLeftChild() {
            return this == parent.left;
        }
    }

    private synchronized Node<T> root() {
        return root;
    }

    private synchronized void setRoot(Node<T> newRoot) {
        this.root = newRoot;
    }

    private synchronized void setParent(Node<T> node, Node<T> parent) {
        if (node != null) {
            node.parent = parent;
        }
    }

    private synchronized void setColor(Node<T> node, boolean color) {
        if (node != null) {
            node.color = color;
        }
    }

    private synchronized boolean getColor(Node<T> node) {
        return node == null ? true : node.color;
    }

    private synchronized void setLeft(Node<T> node, Node<T> left) {
        if (node != null) {
            node.left = left;
        }
    }

    private synchronized void setRight(Node<T> node, Node<T> right) {
        if (node != null) {
            node.right = right;
        }
    }

    private synchronized Node<T> left(Node<T> node) {
        return node == null ? null : node.left;
    }

    private synchronized Node<T> right(Node<T> node) {
        return node == null ? null : node.right;
    }

    private synchronized Node<T> parent(Node<T> node) {
        return node == null ? null : node.parent;
    }

    private synchronized int size(Node<T> node) {
        return node == null ? 0 : node.size;
    }

    private synchronized Node<T> minimum(Node<T> node) {
        while (left(node) != null) {
            node = left(node);
        }
        return node;
    }

    private synchronized Node<T> maximum(Node<T> node) {
        while (right(node) != null) {
            node = right(node);
        }
        return node;
    }

    private synchronized Node<T> successor(Node<T> node) {
        if (right(node) != null) {
            return minimum(right(node));
        }
        Node<T> ancestor = node.parent;
        while (ancestor != null && node == right(ancestor)) {
            node = ancestor;
            ancestor = parent(ancestor);
        }
        return ancestor;
    }

    private synchronized void leftRotate(Node<T> x) {
        Node<T> y = right(x);
        setRight(x, left(y));
        if (left(y) != null) {
            setParent(left(y), x);
        }
        setParent(y, parent(x));
        if (parent(x) == null) {
            setRoot(y);
        } else if (x == left(parent(x))) {
            setLeft(parent(x), y);
        } else {
            setRight(parent(x), y);
        }
        setLeft(y, x);
        setParent(x, y);
        x.size = size(left(x)) + size(right(x)) + 1;
        y.size = size(left(y)) + size(right(y)) + 1;
    }

    private synchronized void rightRotate(Node<T> y) {
        Node<T> x = left(y);
        setLeft(y, right(x));
        if (right(x) != null) {
            setParent(right(x), y);
        }
        setParent(x, parent(y));
        if (parent(y) == null) {
            setRoot(x);
        } else if (y == right(parent(y))) {
            setRight(parent(y), x);
        } else {
            setLeft(parent(y), x);
        }
        setRight(x, y);
        setParent(y, x);
        y.size = size(left(y)) + size(right(y)) + 1;
        x.size = size(left(x)) + size(right(x)) + 1;
    }

    private synchronized void flipColors(Node<T> node) {
        setColor(node, !getColor(node));
        setColor(left(node), !getColor(left(node)));
        setColor(right(node), !getColor(right(node)));
    }

    private synchronized void rotateFixup(Node<T> node) {
        while (node != null && !getColor(parent(node))) {
            if (isLeftChild(node)) {
                Node<T> y = parent(parent(node));
                if (getColor(right(y))) {
                    setColor(y, false);
                    setColor(left(y), true);
                    setColor(parent(node), true);
                    leftRotate(y);
                } else {
                    if (!isLeftChild(parent(node))) {
                        leftRotate(parent(node));
                        y = parent(node);
                    }
                    setColor(y, false);
                    setColor(parent(node), true);
                    rightRotate(y);
                }
            } else {
                Node<T> y = parent(parent(node));
                if (getColor(left(y))) {
                    setColor(y, false);
                    setColor(right(y), true);
                    setColor(parent(node), true);
                    rightRotate(y);
                } else {
                    if (isLeftChild(parent(node))) {
                        rightRotate(parent(node));
                        y = parent(node);
                    }
                    setColor(y, false);
                    setColor(parent(node), true);
                    leftRotate(y);
                }
            }
            node = parent(root());
        }
        setColor(root(), true);
    }

    private synchronized void insertFixup(Node<T> node) {
        while (node != null && !getColor(parent(node))) {
            if (isLeftChild(node)) {
                Node<T> y = parent(parent(node));
                if (getColor(right(y))) {
                    setColor(y, false);
                    setColor(left(y), true);
                    setColor(parent(node), true);
                    leftRotate(y);
                } else {
                    if (!isLeftChild(parent(node))) {
                        leftRotate(parent(node));
                        y = parent(node);
                    }
                    setColor(y, false);
                    setColor(parent(node), true);
                    rightRotate(y);
                }
            } else {
                Node<T> y = parent(parent(node));
                if (getColor(left(y))) {
                    setColor(y, false);
                    setColor(right(y), true);
                    setColor(parent(node), true);
                    rightRotate(y);
                } else {
                    if (isLeftChild(parent(node))) {
                        rightRotate(parent(node));
                        y = parent(node);
                    }
                    setColor(y, false);
                    setColor(parent(node), true);
                    leftRotate(y);
                }
            }
            node = parent(root());
        }
        setColor(root(), true);
    }

    private synchronized Node<T> removeFixup(Node<T> node) {
        while (node != null && !getColor(node)) {
            if (isLeftChild(node)) {
                Node<T> sib = sibling(node);
                if (getColor(sib)) {
                    setColor(sib, false);
                    setColor(parent(node), true);
                    leftRotate(parent(node));
                } else {
                    if (!getColor(right(sib))) {
                        setColor(left(sib), true);
                        setColor(sib, false);
                        rightRotate(sib);
                    }
                    setColor(sib, getColor(parent(node)));
                    setColor(parent(node), true);
                    setLeft(parent(node), null);
                }
            } else {
                Node<T> sib = sibling(node);
                if (getColor(sib)) {
                    setColor(sib, false);
                    setColor(parent(node), true);
                    rightRotate(parent(node));
                } else {
                    if (!getColor(left(sib))) {
                        setColor(right(sib), true);
                        setColor(sib, false);
                        leftRotate(sib);
                    }
                    setColor(sib, getColor(parent(node)));
                    setColor(parent(node), true);
                    setRight(parent(node), null);
                }
            }
        }
        setColor(node, true);
        return node;
    }

    @Override
    public synchronized boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        Node<T> z = new Node<>(item, false);
        if (root == null) {
            setRoot(z);
        } else {
            Node<T> y = null;
            Node<T> x = root();
            while (x != null) {
                y = x;
                if (order.compare(item, x.item) < 0) {
                    x = left(x);
                } else {
                    x = right(x);
                }
            }
            if (order.compare(item, y.item) < 0) {
                setLeft(y, z);
            } else {
                setRight(y, z);
            }
            setParent(z, y);
            color(z, true);
        }
        insertFixup(z);
        return false;
    }

    @Override
    public synchronized boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        Node<T> z = search(item);
        if (z == null) {
            return false;
        }
        Node<T> x, y;
        if (left(z) == null || right(z) == null) {
            y = z;
        } else {
            y = successor(z);
        }
        if (left(y) != null) {
            x = left(y);
        } else {
            x = right(y);
        }
        if (x != null) {
            setParent(x, parent(y));
        }
        if (parent(y) == null) {
            setRoot(x);
        } else if (y == left(parent(y))) {
            setLeft(parent(y), x);
        } else {
            setRight(parent(y), x);
        }
        if (y != z) {
            z.item = y.item;
        }
        if (!getColor(y)) {
            removeFixup(x);
        }
        z.size = size(left(z)) + size(right(z)) + 1;
        return true;
    }

    @Override
    public synchronized boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        return search(item) != null;
    }

    @Override
    public synchronized int size() {
        if (root == null) {
            return 0;
        } else {
            return root.size;
        }
    }

    @Override
    public synchronized T get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException();
        }
        Node<T> x = root;
        int rank = 0;
        while (x != null) {
            if (left(x) != null && left(x).size > index - rank) {
                x = left(x);
            } else {
                if (left(x) != null && left(x).size == index - rank) {
                    return x.item;
                } else {
                    rank += (left(x) == null ? 1 : left(x).size + 1);
                    x = right(x);
                }
            }
        }
        throw new IndexOutOfBoundsException();
    }

    @Override
    public synchronized int rank(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        Node<T> x = root;
        int rank = 0;
        while (x != null) {
            if (order.compare(item, x.item) > 0) {
                rank += (left(x) == null ? 1 : left(x).size + 1);
                x = right(x);
            } else if (order.compare(item, x.item) == 0) {
                rank += (left(x) == null ? 1 : left(x).size + 1);
                break;
            } else {
                x = left(x);
            }
        }
        return rank - 1;
    }

    @Override
    public synchronized Snapshot<T> snapshot() {
        return new Snapshot<>(this);
    }

    private synchronized Node<T> search(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        Node<T> x = root;
        while (x != null) {
            int cmp = order.compare(item, x.item);
            if (cmp == 0) {
                return x;
            } else if (cmp < 0) {
                x = left(x);
            } else {
                x = right(x);
            }
        }
        return null;
    }

    private static class Snapshot<T> {
        private final TreeSet<T> set;
        private final Node<T> root;

        Snapshot(TreeSet<T> set) {
            this.set = set;
            this.root = set.getRoot();
        }

        public int size() {
            return root == null ? 0 : root.size;
        }

        public T get(int index) {
            if (index < 0 || index >= set.size()) {
                throw new IndexOutOfBoundsException();
            }
            Node<T> x = root;
            int rank = 0;
            while (x != null) {
                if (left(x) != null && left(x).size > index - rank) {
                    x = left(x);
                } else {
                    if (left(x) != null && left(x).size == index - rank) {
                        return x.item;
                    } else {
                        rank += (left(x) == null ? 1 : left(x).size + 1);
                        x = right(x);
                    }
                }
            }
            throw new IndexOutOfBoundsException();
        }

        public boolean contains(T item) {
            if (item == null) {
                throw new NullPointerException();
            }
            Node<T> x = root;
            while (x != null) {
                int cmp = set.order.compare(item, x.item);
                if (cmp == 0) {
                    return true;
                } else if (cmp < 0) {
                    x = set.left(x);
                } else {
                    x = set.right(x);
                }
            }
            return false;
        }
    }
}
