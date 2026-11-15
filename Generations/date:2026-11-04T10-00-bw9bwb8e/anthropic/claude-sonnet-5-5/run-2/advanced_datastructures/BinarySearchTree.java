import java.lang.reflect.Array;

interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T item;
        Node<T> left;
        Node<T> right;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Node<T> root;
    private int size;

    public BinarySearchTree(Order<T> order) {
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
                root = new Node<T>(item);
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
                        cur.left = new Node<T>(item);
                        size++;
                        return true;
                    }
                    cur = cur.left;
                } else {
                    if (cur.right == null) {
                        cur.right = new Node<T>(item);
                        size++;
                        return true;
                    }
                    cur = cur.right;
                }
            }
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
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
                Node<T> sp = cur;
                Node<T> s = cur.right;
                while (s.left != null) {
                    sp = s;
                    s = s.left;
                }
                cur.item = s.item;
                Node<T> child = s.right;
                if (sp == cur) {
                    sp.right = child;
                } else {
                    sp.left = child;
                }
            } else {
                Node<T> child = (cur.left != null) ? cur.left : cur.right;
                if (parent == null) {
                    root = child;
                } else if (parent.left == cur) {
                    parent.left = child;
                } else {
                    parent.right = child;
                }
            }
            size--;
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
                throw new IllegalStateException("empty tree");
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
                throw new IllegalStateException("empty tree");
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
            Object[] result = new Object[size];
            Node<T>[] stack = (Node<T>[]) new Node[16];
            int sp = 0;
            int idx = 0;
            Node<T> cur = root;
            while (cur != null || sp > 0) {
                while (cur != null) {
                    if (sp == stack.length) {
                        Node<T>[] bigger = (Node<T>[]) new Node[stack.length * 2];
                        System.arraycopy(stack, 0, bigger, 0, sp);
                        stack = bigger;
                    }
                    stack[sp++] = cur;
                    cur = cur.left;
                }
                cur = stack[--sp];
                stack[sp] = null;
                result[idx++] = cur.item;
                cur = cur.right;
            }
            return result;
        }
    }
}
