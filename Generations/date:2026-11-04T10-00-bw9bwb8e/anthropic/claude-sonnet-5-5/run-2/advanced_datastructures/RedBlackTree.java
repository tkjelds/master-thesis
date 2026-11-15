interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T key;
        Node<T> left;
        Node<T> right;
        Node<T> parent;
        boolean red;

        Node(T key) {
            this.key = key;
            this.red = true;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
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
            int cmp = 0;
            while (cur != null) {
                cmp = order.compare(item, cur.key);
                if (cmp == 0) {
                    return false;
                }
                parent = cur;
                cur = cmp < 0 ? cur.left : cur.right;
            }
            Node<T> z = new Node<T>(item);
            z.parent = parent;
            if (parent == null) {
                root = z;
            } else if (cmp < 0) {
                parent.left = z;
            } else {
                parent.right = z;
            }
            size++;
            fixInsert(z);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> z = root;
            while (z != null) {
                int c = order.compare(item, z.key);
                if (c == 0) {
                    break;
                }
                z = c < 0 ? z.left : z.right;
            }
            if (z == null) {
                return false;
            }
            if (z.left != null && z.right != null) {
                Node<T> y = z.right;
                while (y.left != null) {
                    y = y.left;
                }
                z.key = y.key;
                z = y;
            }
            Node<T> child = z.left != null ? z.left : z.right;
            Node<T> parent = z.parent;
            if (child != null) {
                child.parent = parent;
            }
            if (parent == null) {
                root = child;
            } else if (z == parent.left) {
                parent.left = child;
            } else {
                parent.right = child;
            }
            boolean wasBlack = !z.red;
            z.left = null;
            z.right = null;
            z.parent = null;
            size--;
            if (wasBlack) {
                fixDelete(child, parent);
            }
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> cur = root;
            while (cur != null) {
                int c = order.compare(item, cur.key);
                if (c == 0) {
                    return true;
                }
                cur = c < 0 ? cur.left : cur.right;
            }
            return false;
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
            fill(root, out, idx);
            return out;
        }
    }

    private void fill(Node<T> n, Object[] out, int[] idx) {
        // recursion depth is O(log n) because the tree is balanced
        if (n == null) {
            return;
        }
        fill(n.left, out, idx);
        out[idx[0]++] = n.key;
        fill(n.right, out, idx);
    }

    private static <T> boolean isBlack(Node<T> n) {
        return n == null || !n.red;
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

    private void fixInsert(Node<T> z) {
        while (z.parent != null && z.parent.red) {
            Node<T> p = z.parent;
            Node<T> g = p.parent;
            if (p == g.left) {
                Node<T> u = g.right;
                if (u != null && u.red) {
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
                if (u != null && u.red) {
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

    private void fixDelete(Node<T> x, Node<T> parent) {
        while (x != root && isBlack(x)) {
            if (x == parent.left) {
                Node<T> w = parent.right;
                if (w.red) {
                    w.red = false;
                    parent.red = true;
                    rotateLeft(parent);
                    w = parent.right;
                }
                if (isBlack(w.left) && isBlack(w.right)) {
                    w.red = true;
                    x = parent;
                    parent = x.parent;
                } else {
                    if (isBlack(w.right)) {
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
                    break;
                }
            } else {
                Node<T> w = parent.left;
                if (w.red) {
                    w.red = false;
                    parent.red = true;
                    rotateRight(parent);
                    w = parent.left;
                }
                if (isBlack(w.left) && isBlack(w.right)) {
                    w.red = true;
                    x = parent;
                    parent = x.parent;
                } else {
                    if (isBlack(w.left)) {
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
                    break;
                }
            }
        }
        if (x != null) {
            x.red = false;
        }
    }
}
