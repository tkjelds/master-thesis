import java.util.NoSuchElementException;

interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED   = true;
    private static final boolean BLACK = false;

    private Node<T> root;
    private Order<T> order;

    public RedBlackTree(Order<T> order) {
        this.order = order;
    }

    private static class Node<T> {
        T item;
        Node<T> left, right, parent;
        boolean color;

        Node(T item, Node<T> parent) {
            this.item = item;
            this.parent = parent;
            this.color = RED;
        }
    }

    private void fixInsertion(Node<T> x) {
        while (x != root && x.parent.color == RED) {
            if (x.parent == x.parent.parent.left) {
                Node<T> y = x.parent.parent.right;
                if (y != null && y.color == RED) {
                    x.parent.color = BLACK;
                    y.color = BLACK;
                    x.parent.parent.color = RED;
                    x = x.parent.parent;
                } else {
                    if (x == x.parent.right) {
                        x = x.parent;
                        rotateLeft(x);
                    }
                    x.parent.color = BLACK;
                    x.parent.parent.color = RED;
                    rotateRight(x.parent.parent);
                }
            } else {
                Node<T> y = x.parent.parent.left;
                if (y != null && y.color == RED) {
                    x.parent.color = BLACK;
                    y.color = BLACK;
                    x.parent.parent.color = RED;
                    x = x.parent.parent;
                } else {
                    if (x == x.parent.left) {
                        x = x.parent;
                        rotateRight(x);
                    }
                    x.parent.color = BLACK;
                    x.parent.parent.color = RED;
                    rotateLeft(x.parent.parent);
                }
            }
        }
        root.color = BLACK;
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

    private void rotateRight(Node<T> y) {
        Node<T> x = y.left;
        y.left = x.right;
        if (x.right != null) {
            x.right.parent = y;
        }
        x.parent = y.parent;
        if (y.parent == null) {
            root = x;
        } else if (y == y.parent.right) {
            y.parent.right = x;
        } else {
            y.parent.left = x;
        }
        x.right = y;
        y.parent = x;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> node = new Node<>(item, null);
        if (root == null) {
            root = node;
        } else {
            Node<T> current = root, parent;
            do {
                parent = current;
                int cmp = order.compare(item, current.item);
                if (cmp < 0) {
                    current = current.left;
                } else if (cmp > 0) {
                    current = current.right;
                } else {
                    return false; // Duplicate item
                }
            } while (current != null);
            node.parent = parent;
            if (order.compare(item, parent.item) < 0) {
                parent.left = node;
            } else {
                parent.right = node;
            }
        }
        fixInsertion(node);
        return true;
    }

    private Node<T> treeMinimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private void fixDeletion(Node<T> x) {
        while (x != root && x.color == BLACK) {
            if (x == x.parent.left) {
                Node<T> w = x.parent.right;
                if (w.color == RED) {
                    w.color = BLACK;
                    x.parent.color = RED;
                    rotateLeft(x.parent);
                    w = x.parent.right;
                }
                if ((w.left == null || w.left.color == BLACK) && (w.right == null || w.right.color == BLACK)) {
                    w.color = RED;
                    x = x.parent;
                } else {
                    if (w.right == null || w.right.color == BLACK) {
                        w.left.color = BLACK;
                        w.color = RED;
                        rotateRight(w);
                        w = x.parent.right;
                    }
                    w.color = x.parent.color;
                    x.parent.color = BLACK;
                    if (w.right != null) w.right.color = BLACK;
                    rotateLeft(x.parent);
                    x = root;
                }
            } else {
                Node<T> w = x.parent.left;
                if (w.color == RED) {
                    w.color = BLACK;
                    x.parent.color = RED;
                    rotateRight(x.parent);
                    w = x.parent.left;
                }
                if ((w.right == null || w.right.color == BLACK) && (w.left == null || w.left.color == BLACK)) {
                    w.color = RED;
                    x = x.parent;
                } else {
                    if (w.left == null || w.left.color == BLACK) {
                        w.right.color = BLACK;
                        w.color = RED;
                        rotateLeft(w);
                        w = x.parent.left;
                    }
                    w.color = x.parent.color;
                    x.parent.color = BLACK;
                    if (w.left != null) w.left.color = BLACK;
                    rotateRight(x.parent);
                    x = root;
                }
            }
        }
        x.color = BLACK;
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> z = root;
        while (z != null && order.compare(item, z.item) != 0) {
            if (order.compare(item, z.item) < 0) {
                z = z.left;
            } else {
                z = z.right;
            }
        }
        if (z == null) return false; // Item not found

        Node<T> y = z;
        boolean yOriginalColor = y.color;
        Node<T> x;

        if (z.left == null) {
            x = z.right;
            replaceNode(z, z.right);
        } else if (z.right == null) {
            x = z.left;
            replaceNode(z, z.left);
        } else {
            y = treeMinimum(z.right);
            yOriginalColor = y.color;
            x = y.right;

            if (y.parent == z) {
                x.parent = y;
            } else {
                replaceNode(y, y.right);
                y.right = z.right;
                if (y.right != null) {
                    y.right.parent = y;
                }
            }

            replaceNode(z, y);
            y.left = z.left;
            if (y.left != null) {
                y.left.parent = y;
            }
            y.color = z.color;
        }

        if (yOriginalColor == BLACK) {
            fixDeletion(x);
        }
        return true;
    }

    private void replaceNode(Node<T> u, Node<T> v) {
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

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> current = root;
        while (current != null) {
            int cmp = order.compare(item, current.item);
            if (cmp < 0) {
                current = current.left;
            } else if (cmp > 0) {
                current = current.right;
            } else {
                return true; // Item found
            }
        }
        return false; // Item not found
    }

    public synchronized T first() {
        if (root == null) throw new IllegalStateException();
        Node<T> current = root;
        while (current.left != null) {
            current = current.left;
        }
        return current.item;
    }

    public synchronized T last() {
        if (root == null) throw new IllegalStateException();
        Node<T> current = root;
        while (current.right != null) {
            current = current.right;
        }
        return current.item;
    }

    public synchronized int size() {
        int count = 0;
        Node<T> current = root;
        while (current != null) {
            if (current.color == BLACK) count++;
            current = current.right;
        }
        return count;
    }

    public synchronized Object[] snapshot() {
        Node<T> current = root, first = null, last = null;
        int count = 0;

        while (current != null) {
            if (current.color == BLACK) count++;
            current = current.right;
        }

        Object[] result = new Object[count];
        int i = 0;

        current = root;
        while (current != null) {
            if (first == null && current.color == BLACK) first = current;
            result[i++] = current.item;
            if (last == null && current.color == BLACK) last = current;
            current = current.right;
        }

        return result;
    }
}
