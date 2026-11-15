interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public final class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private static final class Node<T> {
        T element;
        Node<T> left;
        Node<T> right;
        Node<T> parent;
        boolean red;

        Node(T element, Node<T> parent) {
            this.element = element;
            this.parent = parent;
            this.red = RED;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;
    private int size;

    /*
     * Policy: no operation blocks. Null arguments throw NullPointerException
     * before any mutation. first() and last() throw IllegalStateException
     * immediately when the set is empty. All state is guarded by a single
     * monitor (lock), so every operation is linearizable and readers never
     * observe intermediate rotations or partially repaired deletion state.
     */
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
                Node<T> n = new Node<>(item, null);
                n.red = BLACK;
                root = n;
                size++;
                return true;
            }
            Node<T> cur = root;
            Node<T> parent = null;
            int c = 0;
            while (cur != null) {
                parent = cur;
                c = order.compare(item, cur.element);
                if (c == 0) {
                    return false;
                }
                cur = (c < 0) ? cur.left : cur.right;
            }
            Node<T> n = new Node<>(item, parent);
            if (c < 0) {
                parent.left = n;
            } else {
                parent.right = n;
            }
            size++;
            fixAfterInsertion(n);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> n = findNode(item);
            if (n == null) {
                return false;
            }
            deleteNode(n);
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
            return n.element;
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
            return n.element;
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

    private void collect(Node<T> n, Object[] out, int[] idx) {
        if (n == null) {
            return;
        }
        collect(n.left, out, idx);
        out[idx[0]++] = n.element;
        collect(n.right, out, idx);
    }

    private Node<T> findNode(T item) {
        Node<T> cur = root;
        while (cur != null) {
            int c = order.compare(item, cur.element);
            if (c == 0) {
                return cur;
            }
            cur = (c < 0) ? cur.left : cur.right;
        }
        return null;
    }

    private static boolean isRed(Node<?> n) {
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

    private void fixAfterInsertion(Node<T> x) {
        while (x.parent != null && x.parent.red) {
            Node<T> p = x.parent;
            Node<T> g = p.parent; // red parent implies a non-root parent, so g != null
            if (p == g.left) {
                Node<T> u = g.right;
                if (isRed(u)) {
                    p.red = BLACK;
                    u.red = BLACK;
                    g.red = RED;
                    x = g;
                } else {
                    if (x == p.right) {
                        x = p;
                        rotateLeft(x);
                        p = x.parent;
                    }
                    p.red = BLACK;
                    g.red = RED;
                    rotateRight(g);
                }
            } else {
                Node<T> u = g.left;
                if (isRed(u)) {
                    p.red = BLACK;
                    u.red = BLACK;
                    g.red = RED;
                    x = g;
                } else {
                    if (x == p.left) {
                        x = p;
                        rotateRight(x);
                        p = x.parent;
                    }
                    p.red = BLACK;
                    g.red = RED;
                    rotateLeft(g);
                }
            }
        }
        root.red = BLACK;
    }

    private void deleteNode(Node<T> z) {
        if (z.left != null && z.right != null) {
            Node<T> s = z.right;
            while (s.left != null) {
                s = s.left;
            }
            z.element = s.element;
            z = s;
        }
        // z now has at most one child
        Node<T> child = (z.left != null) ? z.left : z.right;
        Node<T> p = z.parent;
        if (child != null) {
            child.parent = p;
        }
        if (p == null) {
            root = child;
        } else if (z == p.left) {
            p.left = child;
        } else {
            p.right = child;
        }
        boolean wasBlack = !z.red;
        z.left = null;
        z.right = null;
        z.parent = null;
        if (wasBlack) {
            fixAfterDeletion(child, p);
        }
    }

    private void fixAfterDeletion(Node<T> x, Node<T> parent) {
        while (x != root && !isRed(x)) {
            if (x == parent.left) {
                Node<T> w = parent.right;
                if (isRed(w)) {
                    w.red = BLACK;
                    parent.red = RED;
                    rotateLeft(parent);
                    w = parent.right;
                }
                if (!isRed(w.left) && !isRed(w.right)) {
                    w.red = RED;
                    x = parent;
                    parent = x.parent;
                } else {
                    if (!isRed(w.right)) {
                        w.left.red = BLACK;
                        w.red = RED;
                        rotateRight(w);
                        w = parent.right;
                    }
                    w.red = parent.red;
                    parent.red = BLACK;
                    if (w.right != null) {
                        w.right.red = BLACK;
                    }
                    rotateLeft(parent);
                    x = root;
                    parent = null;
                }
            } else {
                Node<T> w = parent.left;
                if (isRed(w)) {
                    w.red = BLACK;
                    parent.red = RED;
                    rotateRight(parent);
                    w = parent.left;
                }
                if (!isRed(w.left) && !isRed(w.right)) {
                    w.red = RED;
                    x = parent;
                    parent = x.parent;
                } else {
                    if (!isRed(w.left)) {
                        w.right.red = BLACK;
                        w.red = RED;
                        rotateLeft(w);
                        w = parent.left;
                    }
                    w.red = parent.red;
                    parent.red = BLACK;
                    if (w.left != null) {
                        w.left.red = BLACK;
                    }
                    rotateRight(parent);
                    x = root;
                    parent = null;
                }
            }
        }
        if (x != null) {
            x.red = BLACK;
        }
        if (root != null) {
            root.red = BLACK;
        }
    }
}
