import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
class Snapshot<T> {
    @interface ThreadSafe {}

    final TreeBag<T> treeBag;
    final int size;
    final int distinctSize;
    final int count;

    Snapshot(TreeBag<T> treeBag, int size, int distinctSize, int count) {
        this.treeBag = treeBag;
        this.size = size;
        this.distinctSize = distinctSize;
        this.count = count;
    }

    int size() {
        return size;
    }

    int distinctSize() {
        return distinctSize;
    }

    int count(T item) {
        return treeBag.count(item);
    }

    T get(int index) {
        return treeBag.get(index);
    }
}

@Node.ThreadSafe
class Node<T> {
    @interface ThreadSafe {}

    T item;
    int count;
    Node<T> left, right, parent;
    boolean color;

    Node(T item) {
        this.item = item;
        this.count = 1;
        this.left = null;
        this.right = null;
        this.parent = null;
        this.color = true; // Red
    }
}

@TreeBag.ThreadSafe
class TreeBag<T> {
    @interface ThreadSafe {}

    private Order<T> order;
    private Node<T> root;
    private int size = 0;
    private int distinctSize = 0;
    private final Lock lock = new ReentrantLock();

    TreeBag(Order<T> order) {
        this.order = order;
    }

    void add(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException("Item cannot be null");
            Node<T> node = find(item);
            if (node == null) {
                root = insert(new Node<>(item), null, true);
                distinctSize++;
            } else {
                node.count++;
            }
            size += node.count;
        } finally {
            lock.unlock();
        }
    }

    boolean remove(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException("Item cannot be null");
            Node<T> node = find(item);
            if (node == null) return false;
            node.count--;
            if (node.count == 0) {
                delete(node);
                distinctSize--;
            }
            size -= node.count;
            return true;
        } finally {
            lock.unlock();
        }
    }

    int count(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException("Item cannot be null");
            Node<T> node = find(item);
            return node == null ? 0 : node.count;
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    int distinctSize() {
        lock.lock();
        try {
            return distinctSize;
        } finally {
            lock.unlock();
        }
    }

    T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index out of bounds");
        lock.lock();
        try {
            return treeSelect(root, index + 1);
        } finally {
            lock.unlock();
        }
    }

    int rank(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException("Item cannot be null");
            Node<T> node = find(item);
            if (node == null) return 0;
            int rank = 0;
            Node<T> current = node.left;
            while (current != null) {
                rank += current.count;
                current = current.right;
            }
            return rank;
        } finally {
            lock.unlock();
        }
    }

    Snapshot<T> snapshot() {
        return new Snapshot<>(this, size, distinctSize, count(root.item));
    }

    private Node<T> find(T item) {
        return treeSearch(root, new Node<>(item));
    }

    private Node<T> treeSearch(Node<T> node, Node<T> x) {
        if (node == null || order.compare(node.item, x.item) == 0) return node;
        int cmp = order.compare(x.item, node.item);
        if (cmp < 0) return treeSearch(node.left, x);
        else return treeSearch(node.right, x);
    }

    private Node<T> insert(Node<T> z, Node<T> y, boolean color) {
        if (y == null) return z;
        else if (order.compare(z.item, y.item) < 0) {
            Node<T> left = insert(z, y.left, color);
            y.left = left;
            left.parent = y;
        } else if (order.compare(z.item, y.item) > 0) {
            Node<T> right = insert(z, y.right, color);
            y.right = right;
            right.parent = y;
        } else z.count += color ? 0 : 1;
        return balance(y);
    }

    private Node<T> delete(Node<T> z) {
        if (z == null) return null;
        Node<T> x, y = z.left != null ? z : z.right;
        if (y == null) {
            x = null;
            y = z.parent;
        } else if (z.right == null) {
            x = z.left;
            y = x.parent = z.parent;
        } else {
            Node<T> s = minimum(z.right);
            y = s.parent;
            x = s.right;
            if (s.parent == z) y.left = x;
            else {
                y.left = s.right;
                s.right.parent = y;
            }
        }
        if (y == null) root = x; // empty tree
        else {
            x.parent = y;
            if (y.left == z) y.left = x;
            else y.right = x;
            balance(y);
        }
        return z;
    }

    private Node<T> minimum(Node<T> node) {
        while (node.left != null) node = node.left;
        return node;
    }

    private Node<T> balance(Node<T> z) {
        if (z.color == false) return z;
        if (z.parent != null && z.parent.color == true) {
            Node<T> y = z.parent;
            Node<T> g = y.parent;
            if (g.left == y) {
                Node<T> r = g.right;
                if (r != null && r.color == false) {
                    y.color = true;
                    r.color = true;
                    g.color = false;
                    z = g;
                } else {
                    if (z == y.right) {
                        y = rotateLeft(y);
                        z = y.left;
                        g = y.parent;
                    }
                    y.color = true;
                    g.color = false;
                    z = rotateRight(g);
                }
            } else {
                Node<T> l = g.left;
                if (l != null && l.color == false) {
                    y.color = true;
                    l.color = true;
                    g.color = false;
                    z = g;
                } else {
                    if (z == y.left) {
                        y = rotateRight(y);
                        z = y.right;
                        g = y.parent;
                    }
                    y.color = true;
                    g.color = false;
                    z = rotateLeft(g);
                }
            }
        }
        if (z == root) z.color = true;
        return z;
    }

    private Node<T> rotateLeft(Node<T> x) {
        Node<T> y = x.right;
        x.right = y.left;
        if (y.left != null) y.left.parent = x;
        y.parent = x.parent;
        if (x.parent == null) root = y;
        else if (x == x.parent.left) x.parent.left = y;
        else x.parent.right = y;
        y.left = x;
        x.parent = y;
        return y;
    }

    private Node<T> rotateRight(Node<T> x) {
        Node<T> y = x.left;
        x.left = y.right;
        if (y.right != null) y.right.parent = x;
        y.parent = x.parent;
        if (x.parent == null) root = y;
        else if (x == x.parent.right) x.parent.right = y;
        else x.parent.left = y;
        y.right = x;
        x.parent = y;
        return y;
    }

    private T treeSelect(Node<T> node, int k) {
        if (node == null) return null;
        int t = (node.left != null ? node.left.count : 0) + 1;
        if (t == k) return node.item;
        else if (k < t) return treeSelect(node.left, k);
        else return treeSelect(node.right, k - t);
    }
}
