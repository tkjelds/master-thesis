import java.lang.*;

interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}


    private static final class Node {
        Object item;
        Node left;
        Node right;
        Node parent;
        boolean red;

        Node(Object item) {
            this.item = item;
            this.red = true;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node root;
    private int count;

    public RedBlackTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    @SuppressWarnings("unchecked")
    private int cmp(T a, Object b) {
        return order.compare(a, (T) b);
    }

    private static boolean isRed(Node n) {
        return n != null && n.red;
    }

    private void rotateLeft(Node x) {
        Node y = x.right;
        x.right = y.left;
        if (y.left != null) {
            y.left.parent = x;
        }
        y.parent = x.parent;
        if (x.parent == null) {
            root = y;
        } else if (x == x.parent.left) {
            x.parent.left = y;
        } else {
            x.parent.right = y;
        }
        y.left = x;
        x.parent = y;
    }

    private void rotateRight(Node x) {
        Node y = x.left;
        x.left = y.right;
        if (y.right != null) {
            y.right.parent = x;
        }
        y.parent = x.parent;
        if (x.parent == null) {
            root = y;
        } else if (x == x.parent.right) {
            x.parent.right = y;
        } else {
            x.parent.left = y;
        }
        y.right = x;
        x.parent = y;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node parent = null;
            Node cur = root;
            int c = 0;
            while (cur != null) {
                parent = cur;
                c = cmp(item, cur.item);
                if (c == 0) {
                    return false;
                }
                cur = c < 0 ? cur.left : cur.right;
            }
            Node n = new Node(item);
            n.parent = parent;
            if (parent == null) {
                root = n;
            } else if (c < 0) {
                parent.left = n;
            } else {
                parent.right = n;
            }
            fixInsert(n);
            count++;
            return true;
        }
    }

    private void fixInsert(Node z) {
        while (z.parent != null && z.parent.red) {
            Node p = z.parent;
            Node g = p.parent; // non-null since p is red, so not root
            if (p == g.left) {
                Node u = g.right;
                if (isRed(u)) {
                    p.red = false;
                    u.red = false;
                    g.red = true;
                    z = g;
                } else {
                    if (z == p.right) {
                        z = p;
                        rotateLeft(z);
                        p = z.parent;
                    }
                    p.red = false;
                    g.red = true;
                    rotateRight(g);
                }
            } else {
                Node u = g.left;
                if (isRed(u)) {
                    p.red = false;
                    u.red = false;
                    g.red = true;
                    z = g;
                } else {
                    if (z == p.left) {
                        z = p;
                        rotateRight(z);
                        p = z.parent;
                    }
                    p.red = false;
                    g.red = true;
                    rotateLeft(g);
                }
            }
        }
        root.red = false;
    }

    private Node find(T item) {
        Node cur = root;
        while (cur != null) {
            int c = cmp(item, cur.item);
            if (c == 0) {
                return cur;
            }
            cur = c < 0 ? cur.left : cur.right;
        }
        return null;
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return find(item) != null;
        }
    }

    private void transplant(Node u, Node v) {
        if (u.parent == null) {
            root = v;
        } else if (u == u.parent.left) {
            u.parent.left = v;
        } else {
            u.parent.right = v;
        }
        if (v != null) {
            v.parent = u.parent;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node z = find(item);
            if (z == null) {
                return false;
            }
            Node y = z;
            boolean yWasRed = y.red;
            Node x;
            Node xParent;
            if (z.left == null) {
                x = z.right;
                xParent = z.parent;
                transplant(z, z.right);
            } else if (z.right == null) {
                x = z.left;
                xParent = z.parent;
                transplant(z, z.left);
            } else {
                y = z.right;
                while (y.left != null) {
                    y = y.left;
                }
                yWasRed = y.red;
                x = y.right;
                if (y.parent == z) {
                    xParent = y;
                } else {
                    xParent = y.parent;
                    transplant(y, y.right);
                    y.right = z.right;
                    y.right.parent = y;
                }
                transplant(z, y);
                y.left = z.left;
                y.left.parent = y;
                y.red = z.red;
            }
            if (!yWasRed) {
                fixDelete(x, xParent);
            }
            z.left = null;
            z.right = null;
            z.parent = null;
            z.item = null;
            count--;
            return true;
        }
    }

    private void fixDelete(Node x, Node parent) {
        while (x != root && !isRed(x)) {
            if (x == parent.left) {
                Node w = parent.right;
                if (isRed(w)) {
                    w.red = false;
                    parent.red = true;
                    rotateLeft(parent);
                    w = parent.right;
                }
                if (!isRed(w.left) && !isRed(w.right)) {
                    w.red = true;
                    x = parent;
                    parent = x.parent;
                } else {
                    if (!isRed(w.right)) {
                        w.left.red = false;
                        w.red = true;
                        rotateRight(w);
                        w = parent.right;
                    }
                    w.red = parent.red;
                    parent.red = false;
                    w.right.red = false;
                    rotateLeft(parent);
                    x = root;
                    parent = null;
                }
            } else {
                Node w = parent.left;
                if (isRed(w)) {
                    w.red = false;
                    parent.red = true;
                    rotateRight(parent);
                    w = parent.left;
                }
                if (!isRed(w.left) && !isRed(w.right)) {
                    w.red = true;
                    x = parent;
                    parent = x.parent;
                } else {
                    if (!isRed(w.left)) {
                        w.right.red = false;
                        w.red = true;
                        rotateLeft(w);
                        w = parent.left;
                    }
                    w.red = parent.red;
                    parent.red = false;
                    w.left.red = false;
                    rotateRight(parent);
                    x = root;
                    parent = null;
                }
            }
        }
        if (x != null) {
            x.red = false;
        }
    }

    @SuppressWarnings("unchecked")
    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            Node n = root;
            while (n.left != null) {
                n = n.left;
            }
            return (T) n.item;
        }
    }

    @SuppressWarnings("unchecked")
    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            Node n = root;
            while (n.right != null) {
                n = n.right;
            }
            return (T) n.item;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[count];
            fill(root, out, 0);
            return out;
        }
    }

    private int fill(Node n, Object[] out, int idx) {
        if (n == null) {
            return idx;
        }
        idx = fill(n.left, out, idx);
        out[idx++] = n.item;
        return fill(n.right, out, idx);
    }
}
