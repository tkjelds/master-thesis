interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public final class BinarySearchTree<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T item;
        Node<T> left;
        Node<T> right;

        Node(T item) {
            this.item = item;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;
    private int size;

    public BinarySearchTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    // Policy: no operation blocks. first/last on an empty tree throw
    // IllegalStateException immediately. Every public operation holds the
    // single tree-wide monitor for its full duration, which makes each
    // operation linearizable.

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (root == null) {
                root = new Node<>(item);
                size++;
                return true;
            }
            Node<T> cur = root;
            while (true) {
                int c = order.compare(item, cur.item);
                if (c == 0) {
                    return false;
                }
                if (c < 0) {
                    if (cur.left == null) {
                        cur.left = new Node<>(item);
                        size++;
                        return true;
                    }
                    cur = cur.left;
                } else {
                    if (cur.right == null) {
                        cur.right = new Node<>(item);
                        size++;
                        return true;
                    }
                    cur = cur.right;
                }
            }
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<T> parent = null;
            Node<T> cur = root;
            while (cur != null) {
                int c = order.compare(item, cur.item);
                if (c == 0) {
                    break;
                }
                parent = cur;
                cur = (c < 0) ? cur.left : cur.right;
            }
            if (cur == null) {
                return false;
            }

            if (cur.left != null && cur.right != null) {
                // Two children: replace with in-order successor (minimum of right subtree).
                Node<T> sParent = cur;
                Node<T> s = cur.right;
                while (s.left != null) {
                    sParent = s;
                    s = s.left;
                }
                cur.item = s.item;
                // s has no left child; splice it out by attaching its right subtree.
                replaceChild(sParent, s, s.right);
            } else {
                // Zero or one child.
                Node<T> child = (cur.left != null) ? cur.left : cur.right;
                replaceChild(parent, cur, child);
            }
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<T> cur = root;
            while (cur != null) {
                int c = order.compare(item, cur.item);
                if (c == 0) {
                    return true;
                }
                cur = (c < 0) ? cur.left : cur.right;
            }
            return false;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            Node<T> cur = root;
            while (cur.left != null) {
                cur = cur.left;
            }
            return cur.item;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            Node<T> cur = root;
            while (cur.right != null) {
                cur = cur.right;
            }
            return cur.item;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    @SuppressWarnings("unchecked")
    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[size];
            if (size == 0) {
                return out;
            }
            Object[] stackRaw = new Object[size];
            int top = 0;
            int idx = 0;
            Node<T> cur = root;
            while (cur != null || top > 0) {
                while (cur != null) {
                    stackRaw[top++] = cur;
                    cur = cur.left;
                }
                cur = (Node<T>) stackRaw[--top];
                out[idx++] = cur.item;
                cur = cur.right;
            }
            return out;
        }
    }

    // Must be called while holding lock.
    private void replaceChild(Node<T> parent, Node<T> old, Node<T> replacement) {
        if (parent == null) {
            root = replacement;
        } else if (parent.left == old) {
            parent.left = replacement;
        } else {
            parent.right = replacement;
        }
    }

    private static void requireNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
    }
}
