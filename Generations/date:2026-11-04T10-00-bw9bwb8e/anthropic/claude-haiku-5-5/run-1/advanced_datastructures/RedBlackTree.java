interface Order<T> {
    int compare(T left, T right);
}

/**
 * Concurrent red-black-tree set.
 *
 * Concurrency policy: every public operation runs entirely inside a single
 * tree-wide monitor (synchronized block on a private lock object), so every
 * operation is linearizable and no reader can observe intermediate rotations
 * or partially repaired deletion state. No operation blocks waiting for a
 * state change: first() and last() throw IllegalStateException immediately
 * when the set is empty, and the set is unbounded so add() never waits for
 * capacity.
 */
@RedBlackTree.ThreadSafe
public final class RedBlackTree<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T key;
        Node<T> left;
        Node<T> right;
        Node<T> parent;
        boolean red;

        Node(T key, Node<T> parent) {
            this.key = key;
            this.parent = parent;
            this.red = true;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;
    private int size;

    public RedBlackTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (root == null) {
                root = new Node<T>(item, null);
                root.red = false;
                size = 1;
                return true;
            }
            Node<T> cur = root;
            Node<T> parent = null;
            int cmp = 0;
            while (cur != null) {
                parent = cur;
                cmp = order.compare(item, cur.key);
                if (cmp == 0) {
                    return false;
                }
                cur = cmp < 0 ? cur.left : cur.right;
            }
            Node<T> z = new Node<T>(item, parent);
            if (cmp < 0) {
                parent.left = z;
            } else {
                parent.right = z;
            }
            size++;
            fixupInsert(z);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> z = findNode(item);
            if (z == null) {
                return false;
            }
            deleteNode(z);
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return findNode(item) != null;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            Node<T> n = root;
            while (n.left != null) {
                n = n.left;
            }
            return n.key;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            Node<T> n = root;
            while (n.right != null) {
                n = n.right;
            }
            return n.key;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[size];
            int[] idx = new int[1];
            collect(root, out, idx);
            return out;
        }
    }

    // ---- internal helpers; all called while holding lock ----

    private Node<T> findNode(T item) {
        Node<T> cur = root;
        while (cur != null) {
            int cmp = order.compare(item, cur.key);
            if (cmp == 0) {
                return cur;
            }
            cur = cmp < 0 ? cur.left : cur.right;
        }
        return null;
    }

    private void collect(Node<T> n, Object[] out, int[] idx) {
        if (n == null) {
            return;
        }
        collect(n.left, out, idx);
        out[idx[0]++] = n.key;
        collect(n.right, out, idx);
    }

    private static <T> boolean isRed(Node<T> n) {
        return n != null && n.red;
    }

    private void rotateLeft(Node<T> x) {
        Node<T> y = x.right;
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

    private void rotateRight(Node<T> x) {
        Node<T> y = x.left;
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

    private void fixupInsert(Node<T> z) {
        while (z.parent != null && z.parent.red) {
            Node<T> p = z.parent;
            Node<T> g = p.parent; // non-null: a red parent is never the root
            if (p == g.left) {
                Node<T> u = g.right;
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
                Node<T> u = g.left;
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

    private void transplant(Node<T> u, Node<T> v) {
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

    private void deleteNode(Node<T> z) {
        Node<T> x;
        Node<T> xParent;
        boolean yOriginalRed;

        if (z.left == null) {
            yOriginalRed = z.red;
            x = z.right;
            xParent = z.parent;
            transplant(z, z.right);
        } else if (z.right == null) {
            yOriginalRed = z.red;
            x = z.left;
            xParent = z.parent;
            transplant(z, z.left);
        } else {
            Node<T> y = z.right;
            while (y.left != null) {
                y = y.left;
            }
            yOriginalRed = y.red;
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

        if (!yOriginalRed) {
            fixupDelete(x, xParent);
        }
    }

    private void fixupDelete(Node<T> x, Node<T> parent) {
        while (x != root && !isRed(x)) {
            if (x == parent.left) {
                Node<T> w = parent.right;
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
                    if (w.right != null) {
                        w.right.red = false;
                    }
                    rotateLeft(parent);
                    x = root;
                    parent = null;
                }
            } else {
                Node<T> w = parent.left;
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
                    if (w.left != null) {
                        w.left.red = false;
                    }
                    rotateRight(parent);
                    x = root;
                    parent = null;
                }
            }
        }
        if (x != null) {
            x.red = false;
        }
        if (root != null) {
            root.red = false;
        }
    }
}
