@RedBlackTree.ThreadSafe
public class RedBlackTree {
    @interface ThreadSafe {}

    private static final boolean RED = false;
    private static final boolean BLACK = true;

    private static final class Node {
        int key;
        int value;
        Node left;
        Node right;
        Node parent;
        boolean color;

        Node(int key, int value, Node left, Node right, Node parent, boolean color) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.parent = parent;
            this.color = color;
        }
    }

    private final Node nil;
    private Node root;
    private int size;

    /*
     * Blocking policy:
     * - takeMin()/takeMax() block while the tree is empty.
     * - pollMin()/pollMax() return null immediately if empty.
     * - All mutating operations call notifyAll() while holding the same monitor.
     * - Every wait is guarded by a while loop to avoid spurious wakeups and
     *   guarantee correct behavior under concurrent access.
     */
    public RedBlackTree() {
        nil = new Node(0, 0, null, null, null, BLACK);
        nil.left = nil;
        nil.right = nil;
        nil.parent = nil;
        root = nil;
        size = 0;
    }

    public static final class Entry {
        public final int key;
        public final int value;

        public Entry(int key, int value) {
            this.key = key;
            this.value = value;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean contains(int key) {
        synchronized (this) {
            return findNode(key) != nil;
        }
    }

    public Integer get(int key) {
        synchronized (this) {
            Node node = findNode(key);
            return node == nil ? null : Integer.valueOf(node.value);
        }
    }

    public boolean insert(int key, int value) {
        synchronized (this) {
            boolean inserted = insertInternal(key, value);
            notifyAll();
            return inserted;
        }
    }

    public boolean update(int key, int value) {
        synchronized (this) {
            Node node = findNode(key);
            if (node == nil) {
                boolean inserted = insertInternal(key, value);
                notifyAll();
                return inserted;
            }
            node.value = value;
            notifyAll();
            return true;
        }
    }

    public boolean delete(int key) {
        synchronized (this) {
            boolean removed = deleteInternal(key);
            if (removed) {
                notifyAll();
            }
            return removed;
        }
    }

    public Entry peekMin() {
        synchronized (this) {
            if (root == nil) {
                return null;
            }
            Node min = minimum(root);
            return new Entry(min.key, min.value);
        }
    }

    public Entry peekMax() {
        synchronized (this) {
            if (root == nil) {
                return null;
            }
            Node max = maximum(root);
            return new Entry(max.key, max.value);
        }
    }

    public Entry pollMin() {
        synchronized (this) {
            if (root == nil) {
                return null;
            }
            Entry result = removeMinInternal();
            notifyAll();
            return result;
        }
    }

    public Entry pollMax() {
        synchronized (this) {
            if (root == nil) {
                return null;
            }
            Entry result = removeMaxInternal();
            notifyAll();
            return result;
        }
    }

    public Entry takeMin() {
        synchronized (this) {
            while (root == nil) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            Entry result = removeMinInternal();
            notifyAll();
            return result;
        }
    }

    public Entry takeMax() {
        synchronized (this) {
            while (root == nil) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            Entry result = removeMaxInternal();
            notifyAll();
            return result;
        }
    }

    public void clear() {
        synchronized (this) {
            root = nil;
            size = 0;
            nil.left = nil;
            nil.right = nil;
            nil.parent = nil;
            notifyAll();
        }
    }

    private boolean insertInternal(int key, int value) {
        Node z = new Node(key, value, nil, nil, nil, RED);
        Node y = nil;
        Node x = root;

        while (x != nil) {
            y = x;
            if (key < x.key) {
                x = x.left;
            } else if (key > x.key) {
                x = x.right;
            } else {
                x.value = value;
                return false;
            }
        }

        z.parent = y;
        if (y == nil) {
            root = z;
        } else if (key < y.key) {
            y.left = z;
        } else {
            y.right = z;
        }

        z.left = nil;
        z.right = nil;
        insertFixUp(z);
        size++;
        if (root != nil) {
            root.parent = nil;
        }
        return true;
    }

    private boolean deleteInternal(int key) {
        Node z = findNode(key);
        if (z == nil) {
            return false;
        }

        Node y = z;
        boolean yOriginalColor = y.color;
        Node x;

        if (z.left == nil) {
            x = z.right;
            transplant(z, z.right);
        } else if (z.right == nil) {
            x = z.left;
            transplant(z, z.left);
        } else {
            y = minimum(z.right);
            yOriginalColor = y.color;
            x = y.right;

            if (y.parent == z) {
                x.parent = y;
            } else {
                transplant(y, y.right);
                y.right = z.right;
                y.right.parent = y;
            }

            transplant(z, y);
            y.left = z.left;
            y.left.parent = y;
            y.color = z.color;
        }

        if (yOriginalColor == BLACK) {
            deleteFixUp(x);
        }

        size--;
        if (root != nil) {
            root.parent = nil;
        } else {
            nil.parent = nil;
        }
        return true;
    }

    private Entry removeMinInternal() {
        Node min = minimum(root);
        int key = min.key;
        int value = min.value;
        deleteInternal(key);
        return new Entry(key, value);
    }

    private Entry removeMaxInternal() {
        Node max = maximum(root);
        int key = max.key;
        int value = max.value;
        deleteInternal(key);
        return new Entry(key, value);
    }

    private Node findNode(int key) {
        Node current = root;
        while (current != nil) {
            if (key < current.key) {
                current = current.left;
            } else if (key > current.key) {
                current = current.right;
            } else {
                return current;
            }
        }
        return nil;
    }

    private Node minimum(Node node) {
        while (node.left != nil) {
            node = node.left;
        }
        return node;
    }

    private Node maximum(Node node) {
        while (node.right != nil) {
            node = node.right;
        }
        return node;
    }

    private void insertFixUp(Node z) {
        while (z.parent != nil && z.parent.color == RED) {
            if (z.parent == z.parent.parent.left) {
                Node y = z.parent.parent.right;
                if (y.color == RED) {
                    z.parent.color = BLACK;
                    y.color = BLACK;
                    z.parent.parent.color = RED;
                    z = z.parent.parent;
                } else {
                    if (z == z.parent.right) {
                        z = z.parent;
                        leftRotate(z);
                    }
                    z.parent.color = BLACK;
                    z.parent.parent.color = RED;
                    rightRotate(z.parent.parent);
                }
            } else {
                Node y = z.parent.parent.left;
                if (y.color == RED) {
                    z.parent.color = BLACK;
                    y.color = BLACK;
                    z.parent.parent.color = RED;
                    z = z.parent.parent;
                } else {
                    if (z == z.parent.left) {
                        z = z.parent;
                        rightRotate(z);
                    }
                    z.parent.color = BLACK;
                    z.parent.parent.color = RED;
                    leftRotate(z.parent.parent);
                }
            }
        }
        root.color = BLACK;
    }

    private void deleteFixUp(Node x) {
        while (x != root && x.color == BLACK) {
            if (x == x.parent.left) {
                Node w = x.parent.right;
                if (w.color == RED) {
                    w.color = BLACK;
                    x.parent.color = RED;
                    leftRotate(x.parent);
                    w = x.parent.right;
                }
                if (w.left.color == BLACK && w.right.color == BLACK) {
                    w.color = RED;
                    x = x.parent;
                } else {
                    if (w.right.color == BLACK) {
                        w.left.color = BLACK;
                        w.color = RED;
                        rightRotate(w);
                        w = x.parent.right;
                    }
                    w.color = x.parent.color;
                    x.parent.color = BLACK;
                    w.right.color = BLACK;
                    leftRotate(x.parent);
                    x = root;
                }
            } else {
                Node w = x.parent.left;
                if (w.color == RED) {
                    w.color = BLACK;
                    x.parent.color = RED;
                    rightRotate(x.parent);
                    w = x.parent.left;
                }
                if (w.left.color == BLACK && w.right.color == BLACK) {
                    w.color = RED;
                    x = x.parent;
                } else {
                    if (w.left.color == BLACK) {
                        w.right.color = BLACK;
                        w.color = RED;
                        leftRotate(w);
                        w = x.parent.left;
                    }
                    w.color = x.parent.color;
                    x.parent.color = BLACK;
                    w.left.color = BLACK;
                    rightRotate(x.parent);
                    x = root;
                }
            }
        }
        x.color = BLACK;
    }

    private void leftRotate(Node x) {
        Node y = x.right;
        x.right = y.left;
        if (y.left != nil) {
            y.left.parent = x;
        }

        y.parent = x.parent;
        if (x.parent == nil) {
            root = y;
        } else if (x == x.parent.left) {
            x.parent.left = y;
        } else {
            x.parent.right = y;
        }

        y.left = x;
        x.parent = y;
    }

    private void rightRotate(Node x) {
        Node y = x.left;
        x.left = y.right;
        if (y.right != nil) {
            y.right.parent = x;
        }

        y.parent = x.parent;
        if (x.parent == nil) {
            root = y;
        } else if (x == x.parent.right) {
            x.parent.right = y;
        } else {
            x.parent.left = y;
        }

        y.right = x;
        x.parent = y;
    }

    private void transplant(Node u, Node v) {
        if (u.parent == nil) {
            root = v;
        } else if (u == u.parent.left) {
            u.parent.left = v;
        } else {
            u.parent.right = v;
        }
        v.parent = u.parent;
    }
}
