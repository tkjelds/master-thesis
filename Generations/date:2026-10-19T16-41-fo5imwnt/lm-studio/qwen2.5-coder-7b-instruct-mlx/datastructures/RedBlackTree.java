import java.util.concurrent.atomic.AtomicBoolean;

@RedBlackTreeNode.ThreadSafe
class RedBlackTreeNode<T extends Comparable<T>> {
    @interface ThreadSafe {}

    T key;
    Color color;
    RedBlackTreeNode<T> left, right, parent;

    enum Color { RED, BLACK }

    public RedBlackTreeNode(T key) {
        this.key = key;
        this.color = Color.RED;
        this.left = null;
        this.right = null;
        this.parent = null;
    }
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T extends Comparable<T>> {
    @interface ThreadSafe {}

    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private RedBlackTreeNode<T> root;
    private static final Object MONITOR = new Object();

    public synchronized void insert(T key) {
        RedBlackTreeNode<T> node = new RedBlackTreeNode<>(key);
        RedBlackTreeNode<T> y = null;
        RedBlackTreeNode<T> x = root;

        while (x != null) {
            y = x;
            if (key.compareTo(x.key) < 0)
                x = x.left;
            else
                x = x.right;
        }

        node.parent = y;

        if (y == null)
            root = node;
        else if (key.compareTo(y.key) < 0)
            y.left = node;
        else
            y.right = node;

        if (node.parent == null) {
            node.color = Color.BLACK;
            return;
        }

        if (node.parent.parent == null)
            return;

        fixInsertion(node);
    }

    private void fixInsertion(RedBlackTreeNode<T> z) {
        while (z.parent.color == Color.RED) {
            if (z.parent == z.parent.parent.right) {
                RedBlackTreeNode<T> y = z.parent.parent.left;
                if (y != null && y.color == Color.RED) {
                    y.color = Color.BLACK;
                    z.parent.color = Color.BLACK;
                    z.parent.parent.color = Color.RED;
                    z = z.parent.parent;
                } else {
                    if (z == z.parent.left) {
                        z = z.parent;
                        rightRotate(z);
                    }
                    z.parent.color = Color.BLACK;
                    z.parent.parent.color = Color.RED;
                    leftRotate(z.parent.parent);
                }
            } else {
                RedBlackTreeNode<T> y = z.parent.parent.right;
                if (y != null && y.color == Color.RED) {
                    y.color = Color.BLACK;
                    z.parent.color = Color.BLACK;
                    z.parent.parent.color = Color.RED;
                    z = z.parent.parent;
                } else {
                    if (z == z.parent.right) {
                        z = z.parent;
                        leftRotate(z);
                    }
                    z.parent.color = Color.BLACK;
                    z.parent.parent.color = Color.RED;
                    rightRotate(z.parent.parent);
                }
            }
        }
        root.color = Color.BLACK;
    }

    private void leftRotate(RedBlackTreeNode<T> x) {
        RedBlackTreeNode<T> y = x.right;
        x.right = y.left;
        if (y.left != null)
            y.left.parent = x;

        y.parent = x.parent;
        if (x.parent == null)
            root = y;
        else if (x == x.parent.left)
            x.parent.left = y;
        else
            x.parent.right = y;

        y.left = x;
        x.parent = y;
    }

    private void rightRotate(RedBlackTreeNode<T> y) {
        RedBlackTreeNode<T> x = y.left;
        y.left = x.right;
        if (x.right != null)
            x.right.parent = y;

        x.parent = y.parent;
        if (y.parent == null)
            root = x;
        else if (y == y.parent.right)
            y.parent.right = x;
        else
            y.parent.left = x;

        x.right = y;
        y.parent = x;
    }

    public synchronized boolean search(T key) {
        RedBlackTreeNode<T> x = root;
        while (x != null && !key.equals(x.key)) {
            if (key.compareTo(x.key) < 0)
                x = x.left;
            else
                x = x.right;
        }
        return x != null;
    }

    public synchronized void delete(T key) {
        RedBlackTreeNode<T> z = root;
        while (z != null && !key.equals(z.key)) {
            if (key.compareTo(z.key) < 0)
                z = z.left;
            else
                z = z.right;
        }
        if (z == null) return;

        RedBlackTreeNode<T> y = z, x;
        Color yOriginalColor = y.color;
        if (z.left == null) {
            x = z.right;
            transplant(z, z.right);
        } else if (z.right == null) {
            x = z.left;
            transplant(z, z.left);
        } else {
            y = minimum(z.right);
            yOriginalColor = y.color;
            x = y.right;
            if (y.parent == z)
                x.parent = y;
            else {
                transplant(y, y.right);
                y.right = z.right;
                y.right.parent = y;
            }
            transplant(z, y);
            y.left = z.left;
            y.left.parent = y;
            y.color = z.color;
        }
        if (yOriginalColor == Color.BLACK)
            fixDeletion(x);

        // Ensure the tree is not empty while removing elements
        if (root != null) {
            synchronized (MONITOR) {
                RUNNING.set(true);
            }
        } else {
            synchronized (MONITOR) {
                MONITOR.notifyAll();
                RUNNING.set(false);
            }
        }
    }

    private void fixDeletion(RedBlackTreeNode<T> x) {
        while (x != root && x.color == Color.BLACK) {
            if (x == x.parent.left) {
                RedBlackTreeNode<T> s = x.parent.right;
                if (s.color == Color.RED) {
                    s.color = Color.BLACK;
                    x.parent.color = Color.RED;
                    leftRotate(x.parent);
                    s = x.parent.right;
                }
                if (s.left.color == Color.BLACK && s.right.color == Color.BLACK) {
                    s.color = Color.RED;
                    x = x.parent;
                } else {
                    if (s.right.color == Color.BLACK) {
                        s.left.color = Color.BLACK;
                        s.color = Color.RED;
                        rightRotate(s);
                        s = x.parent.right;
                    }
                    s.color = x.parent.color;
                    x.parent.color = Color.BLACK;
                    s.right.color = Color.BLACK;
                    leftRotate(x.parent);
                    x = root;
                }
            } else {
                RedBlackTreeNode<T> s = x.parent.left;
                if (s.color == Color.RED) {
                    s.color = Color.BLACK;
                    x.parent.color = Color.RED;
                    rightRotate(x.parent);
                    s = x.parent.left;
                }
                if (s.right.color == Color.BLACK && s.left.color == Color.BLACK) {
                    s.color = Color.RED;
                    x = x.parent;
                } else {
                    if (s.left.color == Color.BLACK) {
                        s.right.color = Color.BLACK;
                        s.color = Color.RED;
                        leftRotate(s);
                        s = x.parent.left;
                    }
                    s.color = x.parent.color;
                    x.parent.color = Color.BLACK;
                    s.left.color = Color.BLACK;
                    rightRotate(x.parent);
                    x = root;
                }
            }
        }
        x.color = Color.BLACK;
    }

    private RedBlackTreeNode<T> minimum(RedBlackTreeNode<T> x) {
        while (x.left != null)
            x = x.left;
        return x;
    }

    private void transplant(RedBlackTreeNode<T> u, RedBlackTreeNode<T> v) {
        if (u.parent == null)
            root = v;
        else if (u == u.parent.left)
            u.parent.left = v;
        else
            u.parent.right = v;
        if (v != null)
            v.parent = u.parent;
    }

    public synchronized void clear() {
        root = null;
        RUNNING.set(false);
    }

    public synchronized boolean isRunning() {
        return RUNNING.get();
    }
}
