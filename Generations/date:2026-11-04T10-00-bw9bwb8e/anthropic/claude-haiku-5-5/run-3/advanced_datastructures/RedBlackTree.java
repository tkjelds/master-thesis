interface Order<T> {
    int compare(T left, T right);
}

/**
 * Concurrent red-black-tree set.
 *
 * Blocking policy: no operation blocks. Null arguments throw NullPointerException
 * before any state change. first() and last() throw IllegalStateException immediately
 * when the set is empty. add() returns false for an equivalent existing element and
 * remove() returns false for an absent element. The set is unbounded, so add never waits.
 *
 * All state is guarded by a single private monitor. Every public operation runs entirely
 * inside one synchronized block, so readers never observe intermediate rotations or
 * partially repaired deletion state.
 */
@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T value;
        Node<T> left;
        Node<T> right;
        Node<T> parent;
        boolean red;

        Node(T value) {
            this.value = value;
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
            Node<T> parent = null;
            Node<T> cur = root;
            int c = 0;
            while (cur != null) {
                parent = cur;
                c = order.compare(item, cur.value);
                if (c == 0) {
                    return false;
                }
                cur = (c < 0) ? cur.left : cur.right;
            }
            Node<T> z = new Node<>(item);
            z.parent = parent;
            if (parent == null) {
                root = z;
            } else if (c < 0) {
                parent.left = z;
            } else {
                parent.right = z;
            }
            size++;
            insertFixup(z);
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
            return minimum(root).value;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            return maximum(root).value;
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
            if (root == null) {
                return result;
            }
            Node<T> n = minimum(root);
            for (int i = 0; i < size && n != null; i++) {
                result[i] = n.value;
                n = successor(n);
            }
            return result;
        }
    }

    // ---- internal helpers; all called while holding lock ----

    private Node<T> findNode(T item) {
        Node<T> cur = root;
        while (cur != null) {
            int c = order.compare(item, cur.value);
            if (c == 0) {
                return cur;
            }
            cur = (c < 0) ? cur.left : cur.right;
        }
        return null;
    }

    private static <T> boolean isRed(Node<T> n) {
        return n != null && n.red;
    }

    private static <T> Node<T> minimum(Node<T> n) {
        while (n.left != null) {
            n = n.left;
        }
        return n;
    }

    private static <T> Node<T> maximum(Node<T> n) {
        while (n.right != null) {
            n = n.right;
        }
        return n;
    }

    private static <T> Node<T> successor(Node<T> n) {
        if (n.right != null) {
            return minimum(n.right);
        }
        Node<T> p = n.parent;
        while (p != null && n == p.right) {
            n = p;
            p = p.parent;
        }
        return p;
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

    private void insertFixup(Node<T> z) {
        while (z.parent != null && z.parent.red) {
            Node<T> p = z.parent;
            Node<T> g = p.parent; // non-null because a red parent is never the root
            if (p == g.left) {
                Node<T> y = g.right;
                if (isRed(y)) {
                    p.red = false;
                    y.red = false;
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
                Node<T> y = g.left;
                if (isRed(y)) {
                    p.red = false;
                    y.red = false;
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
        Node<T> y = z;
        boolean yOriginalRed = y.red;
        Node<T> x;
        Node<T> xParent;

        if (z.left == null) {
            x = z.right;
            xParent = z.parent;
            transplant(z, z.right);
        } else if (z.right == null) {
            x = z.left;
            xParent = z.parent;
            transplant(z, z.left);
        } else {
            y = minimum(z.right);
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

        z.left = null;
        z.right = null;
        z.parent = null;

        if (!yOriginalRed) {
            deleteFixup(x, xParent);
        }
    }

    private void deleteFixup(Node<T> x, Node<T> parent) {
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
                if (!isRed(w.right) && !isRed(w.left)) {
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
    }
}
