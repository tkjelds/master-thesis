```java
import java.util.NoSuchElementException;

interface Order<T> {
    int compare(T left, T right);
}

class TreeSet<T> {
    private static final Node EMPTY = new Node(null);
    private final Order<T> order;
    private Node root;
    private int size;

    public TreeSet(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
        this.root = EMPTY;
        this.size = 0;
    }

    public boolean add(T item) {
        synchronized (root) {
            if (item == null) throw new NullPointerException();
            Node newNode = new Node(item);
            Node current = root;
            while (true) {
                int cmp = order.compare(newNode.value, current.value);
                if (cmp == 0) return false;
                Node next = cmp < 0 ? current.left : current.right;
                if (next == EMPTY) {
                    if (cmp < 0) current.left = newNode;
                    else current.right = newNode;
                    newNode.parent = current;
                    newNode.color = Node.BLACK;
                    fixInsertion(newNode);
                    size++;
                    return true;
                }
                current = next;
            }
        }
    }

    public boolean remove(T item) {
        synchronized (root) {
            if (item == null) throw new NullPointerException();
            Node removed = search(root, item);
            if (removed == EMPTY) return false;
            removeNode(removed);
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (root) {
            if (item == null) throw new NullPointerException();
            return search(root, item) != EMPTY;
        }
    }

    public int size() {
        synchronized (root) {
            return size;
        }
    }

    public T get(int index) {
        synchronized (root) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            return select(root, index + 1);
        }
    }

    public int rank(T item) {
        synchronized (root) {
            if (item == null) throw new NullPointerException();
            Node n = search(root, item);
            return n == EMPTY ? size : countLess(n) - (n.color == Node.BLACK ? 1 : 0);
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (root) {
            return new Snapshot<>(this);
        }
    }

    private Node search(Node node, T item) {
        while (node != EMPTY) {
            int cmp = order.compare(item, node.value);
            if (cmp == 0) return node;
            else if (cmp < 0) node = node.left;
            else node = node.right;
        }
        return EMPTY;
    }

    private void removeNode(Node z) {
        Node y = (z.right == EMPTY) ? z : successor(z);
        Node x = (y.right != EMPTY) ? y.right : y.left;
        x.parent = y.parent;
        if (y.parent == EMPTY) root = x;
        else if (y == y.parent.left) y.parent.left = x;
        else y.parent.right = x;

        if (y != z) {
            int oldYColor = y.color;
            y.value = z.value;
            if (oldYColor == Node.BLACK) fixDeletion(x);
        }
    }

    private void fixInsertion(Node z) {
        while (z != root && z.parent.color == Node.RED) {
            if (z.parent == z.parent.parent.left) {
                Node uncle = z.parent.parent.right;
                if (uncle.color == Node.RED) {
                    z.parent.color = Node.BLACK;
                    uncle.color = Node.BLACK;
                    z.parent.parent.color = Node.RED;
                    z = z.parent.parent;
                } else {
                    if (z == z.parent.right) {
                        z = z.parent;
                        rotateLeft(z);
                    }
                    z.parent.color = Node.BLACK;
                    z.parent.parent.color = Node.RED;
                    rotateRight(z.parent.parent);
                }
            } else {
                Node uncle = z.parent.parent.left;
                if (uncle.color == Node.RED) {
                    z.parent.color = Node.BLACK;
                    uncle.color = Node.BLACK;
                    z.parent.parent.color = Node.RED;
                    z = z.parent.parent;
                } else {
                    if (z == z.parent.left) {
                        z = z.parent;
                        rotateRight(z);
                    }
                    z.parent.color = Node.BLACK;
                    z.parent.parent.color = Node.RED;
                    rotateLeft(z.parent.parent);
                }
            }
        }
        root.color = Node.BLACK;
    }

    private void fixDeletion(Node x) {
        while (x != root && x.color == Node.BLACK) {
            if (x == x.parent.left) {
                Node w = x.parent.right;
                if (w.color == Node.RED) {
                    w.color = Node.BLACK;
                    x.parent.color = Node.RED;
                    rotateLeft(x.parent);
                    w = x.parent.right;
                }
                if (w.left.color == Node.BLACK && w.right.color == Node.BLACK) {
                    w.color = Node.RED;
                    x = x.parent;
                } else {
                    if (w.right.color == Node.BLACK) {
                        w.left.color = Node.BLACK;
                        w.color = Node.RED;
                        rotateRight(w);
                        w = x.parent.right;
                    }
                    w.color = x.parent.color;
                    x.parent.color = Node.BLACK;
                    w.right.color = Node.BLACK;
                    rotateLeft(x.parent);
                    x = root;
                }
            } else {
                Node w = x.parent.left;
                if (w.color == Node.RED) {
                    w.color = Node.BLACK;
                    x.parent.color = Node.RED;
                    rotateRight(x.parent);
                    w = x.parent.left;
                }
                if (w.right.color == Node.BLACK && w.left.color == Node.BLACK) {
                    w.color = Node.RED;
                    x = x.parent;
                } else {
                    if (w.left.color == Node.BLACK) {
                        w.right.color = Node.BLACK;
                        w.color = Node.RED;
                        rotateLeft(w);
                        w = x.parent.left;
                    }
                    w.color = x.parent.color;
                    x.parent.color = Node.BLACK;
                    w.left.color = Node.BLACK;
                    rotateRight(x.parent);
                    x = root;
                }
            }
        }
        x.color = Node.BLACK;
    }

    private void rotateLeft(Node x) {
        if (x == EMPTY || x.right == EMPTY) return;
        Node y = x.right;
        x.right = y.left;
        if (y.left != EMPTY) y.left.parent = x;
        y.parent = x.parent;
        if (x.parent == EMPTY) root = y;
        else if (x == x.parent.left) x.parent.left = y;
        else x.parent.right = y;
        y.left = x;
        x.parent = y;
    }

    private void rotateRight(Node y) {
        if (y == EMPTY || y.left == EMPTY) return;
        Node x = y.left;
        y.left = x.right;
        if (x.right != EMPTY) x.right.parent = y;
        x.parent = y.parent;
        if (y.parent == EMPTY) root = x;
        else if (y == y.parent.right) y.parent.right = x;
        else y.parent.left = x;
        x.right = y;
        y.parent = x;
    }

    private Node successor(Node z) {
        if (z.right != EMPTY) return minimum(z.right);
        Node y = z.parent;
        while (y != EMPTY && z == y.right) {
            z = y;
            y = y.parent;
        }
        return y;
    }

    private Node minimum(Node node) {
        while (node.left != EMPTY) node = node.left;
        return node;
    }

    private int countLess(Node x) {
        int cnt = x.color == Node.BLACK ? 1 : 0;
        if (x.left != EMPTY) cnt += countLess(x.left);
        if (x.right != EMPTY) cnt += countLess(x.right);
        return cnt;
    }

    private int select(Node node, int k) {
        while (true) {
            if (node.left == EMPTY) {
                int t = 1;
                while (true) {
                    if (node.color == Node.BLACK) t++;
                    if (t == k) return node.value;
                    node = node.parent;
                    if (node.left != EMPTY && node.left == node.parent.right) t--;
                    if (t <= 0) break;
                }
            } else {
                int t = node.left.count + 1;
                if (t == k) return node.value;
                else if (t > k) {
                    node = node.left;
                } else {
                    t -= 1 + node.left.count;
                    if (node.color == Node.BLACK) t--;
                    node = node.right;
                }
            }
        }
    }

    static class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static class Snapshot<T> {
        private final TreeSet<T> set;

        Snapshot(TreeSet<T> set) {
            this.set = set;
        }

        int size() {
            synchronized (set.root) {
                return set.size;
            }
        }

        T get(int index) {
            synchronized (set.root) {
                if (index < 0 || index >= set.size) throw new IndexOutOfBoundsException();
                return set.select(set.root, index + 1);
            }
        }

        boolean contains(T item) {
            synchronized (set.root) {
                if (item == null) throw new NullPointerException();
                return set.search(set.root, item) != TreeSet.EMPTY;
            }
        }
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock() { return true; }
        public void unlock() {}
    }

    static final class Node {
        T value;
        int count = 1;
        Node parent, left, right;
        boolean color;

        Node(T value) {
            this.value = value;
            this.color = Node.RED;
        }
    }

    static final class EMPTY {
        public void lock() {}
        public boolean tryLock()
```
