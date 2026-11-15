import java.lang.*;

interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T item;
        Node<T> left;
        Node<T> right;
        Node<T> parent;
        boolean red;

        Node(T item, Node<T> parent) {
            this.item = item;
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
                // still validate comparator reflexively is not needed
                Node<T> n = new Node<T>(item, null);
                n.red = false;
                root = n;
                size = 1;
                return true;
            }
            Node<T> cur = root;
            Node<T> par = null;
            int c = 0;
            while (cur != null) {
                par = cur;
                c = order.compare(item, cur.item);
                if (c == 0) {
                    return false;
                }
                cur = c < 0 ? cur.left : cur.right;
            }
            Node<T> n = new Node<T>(item, par);
            if (c < 0) {
                par.left = n;
            } else {
                par.right = n;
            }
            size++;
            fixInsert(n);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> z = find(item);
            if (z == null) {
                return false;
            }
            if (z.left != null && z.right != null) {
                Node<T> y = z.right;
                while (y.left != null) {
                    y = y.left;
                }
                z.item = y.item;
                z = y;
            }
            Node<T> x = z.left != null ? z.left : z.right;
            Node<T> parent = z.parent;
            if (x != null) {
                x.parent = parent;
            }
            if (parent == null) {
                root = x;
            } else if (parent.left == z) {
                parent.left = x;
            } else {
                parent.right = x;
            }
            boolean wasBlack = !z.red;
            z.left = null;
            z.right = null;
            z.parent = null;
            size--;
            if (wasBlack) {
                fixDelete(x, parent);
            }
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return find(item) != null;
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
            return n.item;
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
            return n.item;
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
            Node<T> n = root;
            if (n == null) {
                return out;
            }
            while (n.left != null) {
                n = n.left;
            }
            int i = 0;
            while (n != null) {
                out[i++] = n.item;
                if (n.right != null) {
                    n = n.right;
                    while (n.left != null) {
                        n = n.left;
                    }
                } else {
                    Node<T> p = n.parent;
                    while (p != null && p.right == n) {
                        n = p;
                        p = p.parent;
                    }
                    n = p;
                }
            }
            return out;
        }
    }

    // ---- internals (call with lock held) ----

    private Node<T> find(T item) {
        Node<T> cur = root;
        while (cur != null) {
            int c = order.compare(item, cur.item);
            if (c == 0) {
                return cur;
            }
            cur = c < 0 ? cur.left : cur.right;
        }
        return null;
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
        } else if (x.parent.left == x) {
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
        } else if (x.parent.right == x) {
            x.parent.right = y;
        } else {
            x.parent.left = y;
        }
        y.right = x;
        x.parent = y;
    }

    private void fixInsert(Node<T> n) {
        while (n != root && n.parent.red) {
            Node<T> p = n.parent;
            Node<T> g = p.parent; // exists since p is red, so not root
            if (p == g.left) {
                Node<T> u = g.right;
                if (isRed(u)) {
                    p.red = false;
                    u.red = false;
                    g.red = true;
                    n = g;
                } else {
                    if (n == p.right) {
                        n = p;
                        rotateLeft(n);
                        p = n.parent;
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
                    n = g;
                } else {
                    if (n == p.left) {
                        n = p;
                        rotateRight(n);
                        p = n.parent;
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
                    w.right.red = false;
                    rotateLeft(parent);
                    x = root;
                    break;
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
                    w.left.red = false;
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
