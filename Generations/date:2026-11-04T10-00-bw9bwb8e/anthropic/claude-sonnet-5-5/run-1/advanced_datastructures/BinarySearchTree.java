import java.lang.*; is not needed, so this file has no imports. It uses a single tree-wide monitor, and all iteration is iterative so deep (unbalanced) trees cannot overflow the stack.

interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}


    private static final class Node {
        Object element;
        Node left;
        Node right;

        Node(Object element) {
            this.element = element;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Node root;
    private int count;

    public BinarySearchTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    @SuppressWarnings("unchecked")
    private int cmp(T a, Object b) {
        return order.compare(a, (T) b);
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (root == null) {
                root = new Node(item);
                count++;
                return true;
            }
            Node cur = root;
            while (true) {
                int c = cmp(item, cur.element);
                if (c == 0) {
                    return false;
                }
                if (c < 0) {
                    if (cur.left == null) {
                        cur.left = new Node(item);
                        count++;
                        return true;
                    }
                    cur = cur.left;
                } else {
                    if (cur.right == null) {
                        cur.right = new Node(item);
                        count++;
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
            Node parent = null;
            Node cur = root;
            boolean isLeft = false;
            while (cur != null) {
                int c = cmp(item, cur.element);
                if (c == 0) {
                    break;
                }
                parent = cur;
                if (c < 0) {
                    isLeft = true;
                    cur = cur.left;
                } else {
                    isLeft = false;
                    cur = cur.right;
                }
            }
            if (cur == null) {
                return false;
            }
            if (cur.left != null && cur.right != null) {
                // find in-order successor (min of right subtree)
                Node sp = cur;
                Node s = cur.right;
                while (s.left != null) {
                    sp = s;
                    s = s.left;
                }
                cur.element = s.element;
                if (sp == cur) {
                    sp.right = s.right;
                } else {
                    sp.left = s.right;
                }
            } else {
                Node child = (cur.left != null) ? cur.left : cur.right;
                if (parent == null) {
                    root = child;
                } else if (isLeft) {
                    parent.left = child;
                } else {
                    parent.right = child;
                }
            }
            count--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node cur = root;
            while (cur != null) {
                int c = cmp(item, cur.element);
                if (c == 0) {
                    return true;
                }
                cur = (c < 0) ? cur.left : cur.right;
            }
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            Node cur = root;
            while (cur.left != null) {
                cur = cur.left;
            }
            return (T) cur.element;
        }
    }

    @SuppressWarnings("unchecked")
    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            Node cur = root;
            while (cur.right != null) {
                cur = cur.right;
            }
            return (T) cur.element;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[count];
            int idx = 0;
            // iterative in-order traversal using Morris-free explicit stack
            Node[] stack = new Node[16];
            int sp = 0;
            Node cur = root;
            while (cur != null || sp > 0) {
                while (cur != null) {
                    if (sp == stack.length) {
                        Node[] bigger = new Node[stack.length * 2];
                        for (int i = 0; i < sp; i++) {
                            bigger[i] = stack[i];
                        }
                        stack = bigger;
                    }
                    stack[sp++] = cur;
                    cur = cur.left;
                }
                cur = stack[--sp];
                stack[sp] = null;
                result[idx++] = cur.element;
                cur = cur.right;
            }
            return result;
        }
    }
}
