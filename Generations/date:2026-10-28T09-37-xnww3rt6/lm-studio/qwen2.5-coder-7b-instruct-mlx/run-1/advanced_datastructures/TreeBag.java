import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
class Snapshot<T> {
    @interface ThreadSafe {}

    private final int size;
    private final int distinctSize;
    private final int count;
    private final T item;

    Snapshot(int size, int distinctSize, int count, T item) {
        this.size = size;
        this.distinctSize = distinctSize;
        this.count = count;
        this.item = item;
    }

    int size() {
        return size;
    }

    int distinctSize() {
        return distinctSize;
    }

    int count(T item) {
        if (this.item == null) return 0;
        return this.item.equals(item) ? count : 0;
    }

    T get(int index) {
        if (index == 0 && this.item != null) return item;
        throw new IndexOutOfBoundsException();
    }
}

@TreeBag.ThreadSafe
class TreeBag<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final Node root = new Node();
    private int size = 0;
    private int distinctSize = 0;

    TreeBag(Order<T> order) {
        this.order = order;
    }

    private static class Node {
        T item;
        int count;
        Node left, right;

        Node() {}
    }

    private void fixUp(Node x) {
        while (x != root && x.parent.color == Node.RED) {
            if (x.parent == x.parent.parent.left) {
                Node y = x.parent.parent.right;
                if (y.color == Node.RED) {
                    y.color = Node.BLACK;
                    x.parent.color = Node.BLACK;
                    x.parent.parent.color = Node.RED;
                    x = x.parent.parent;
                } else {
                    if (x == x.parent.right) {
                        x = x.parent;
                        leftRotate(x);
                    }
                    x.parent.color = Node.BLACK;
                    x.parent.parent.color = Node.RED;
                    rightRotate(x.parent.parent);
                }
            } else {
                Node y = x.parent.parent.left;
                if (y.color == Node.RED) {
                    y.color = Node.BLACK;
                    x.parent.color = Node.BLACK;
                    x.parent.parent.color = Node.RED;
                    x = x.parent.parent;
                } else {
                    if (x == x.parent.left) {
                        x = x.parent;
                        rightRotate(x);
                    }
                    x.parent.color = Node.BLACK;
                    x.parent.parent.color = Node.RED;
                    leftRotate(x.parent.parent);
                }
            }
        }
        root.color = Node.BLACK;
    }

    private void leftRotate(Node x) {
        Node y = x.right;
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

    private void rightRotate(Node x) {
        Node y = x.left;
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

    private void insert(T item) {
        Node z = new Node();
        z.item = item;
        z.color = Node.RED;
        Node y = null;
        Node x = root;

        while (x != null) {
            y = x;
            int cmp = order.compare(z.item, x.item);
            if (cmp < 0) {
                x = x.left;
            } else {
                x = x.right;
            }
        }

        z.parent = y;
        if (y == null) {
            root = z;
        } else if (order.compare(z.item, y.item) < 0) {
            y.left = z;
        } else {
            y.right = z;
        }

        if (y == null) {
            z.color = Node.BLACK;
            return;
        }

        if (y.color == Node.BLACK) {
            return;
        }

        fixUp(z);
    }

    private void delete(T item) {
        Node z = root;
        while (z != null) {
            int cmp = order.compare(item, z.item);
            if (cmp < 0) {
                z = z.left;
            } else if (cmp > 0) {
                z = z.right;
            } else {
                break;
            }
        }

        if (z == null) return;

        Node y = z;
        int yOriginalColor = y.color;
        if (z.left == null) {
            Node x = z.right;
            replace(z, x);
        } else if (z.right == null) {
            Node x = z.left;
            replace(z, x);
        } else {
            y = minimum(z.right);
            yOriginalColor = y.color;
            Node x = y.right;
            replace(y, x);
            y.left = z.left;
            y.left.parent = y;
            if (yOriginalColor == Node.BLACK) {
                fixUp(x);
            }
        }

        root.color = Node.BLACK;
    }

    private void replace(Node u, Node v) {
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

    private Node minimum(Node x) {
        while (x.left != null) {
            x = x.left;
        }
        return x;
    }

    public void add(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (root) {
            if ((size + 1) / 2 > Integer.MAX_VALUE) throw new IllegalStateException();
            insert(item);
            size += item instanceof Comparable ? 1 : 0;
            if (order.compare(item, root.item) != 0) distinctSize++;
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (root) {
            delete(item);
            size -= item instanceof Comparable ? 1 : 0;
            if (order.compare(item, root.item) != 0) distinctSize--;
            return size > 0;
        }
    }

    public int count(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (root) {
            Node x = root;
            while (x != null) {
                int cmp = order.compare(item, x.item);
                if (cmp < 0) {
                    x = x.left;
                } else if (cmp > 0) {
                    x = x.right;
                } else {
                    return x.count;
                }
            }
            return 0;
        }
    }

    public int size() {
        synchronized (root) {
            return size;
        }
    }

    public int distinctSize() {
        synchronized (root) {
            return distinctSize;
        }
    }

    public T get(int index) {
        synchronized (root) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
            Node x = root;
            while (true) {
                int leftSize = x.left != null ? x.left.count : 0;
                if (index == leftSize) {
                    return x.item;
                } else if (index < leftSize) {
                    x = x.left;
                } else {
                    index -= leftSize + 1;
                    x = x.right;
                }
            }
        }
    }

    public int rank(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (root) {
            Node x = root;
            int rank = 0;
            while (x != null) {
                int cmp = order.compare(item, x.item);
                if (cmp < 0) {
                    x = x.left;
                } else {
                    if (x.left != null) rank += x.left.count + 1;
                    else rank++;
                    if (cmp == 0) break;
                    x = x.right;
                }
            }
            return rank - (item instanceof Comparable ? 1 : 0);
        }
    }

    public Snapshot<T> snapshot() {
        return new Snapshot<>(size, distinctSize(), count(null), root.item);
    }
}
