import java.util.Arrays;

interface Order<K> {
    int compare(K left, K right);
}

@Entry.ThreadSafe
class Entry<K, V> {
    @interface ThreadSafe {}

    final K key;
    final V value;

    public Entry(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public K key() { return key; }
    public V value() { return value; }
}

@Snapshot.ThreadSafe
class Snapshot<K, V> {
    @interface ThreadSafe {}

    private final TreeDictionary<K, V> tree;

    public Snapshot(TreeDictionary<K, V> tree) {
        this.tree = tree;
    }

    public int size() { return tree.size(); }
    public V get(K key) { return tree.get(key); }
    public Entry<K, V> entryAt(int index) { return tree.entryAt(index); }
}

@TreeDictionary.ThreadSafe
class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private final Order<K> order;
    private Node root = null;
    private int size = 0;

    public TreeDictionary(Order<K> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    private static class Node {
        final K key;
        V value;
        Node left, right, parent;
        boolean red;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            left = right = parent = null;
            red = true; // New nodes are always red
        }
    }

    private void fixInsert(Node z) {
        while (z.parent != null && z.parent.red) {
            Node y = z.parent == z.parent.parent.left ? z.parent.parent.right : z.parent.parent.left;
            if (y != null && y.red) {
                z.parent.red = false;
                y.red = false;
                z.parent.parent.red = true;
                z = z.parent.parent;
            } else {
                if (z == z.parent.right && z.parent == z.parent.parent.left) {
                    z = z.parent;
                    rotateLeft(z);
                }
                z.parent.red = false;
                z.parent.parent.red = true;
                rotateRight(z.parent.parent);
            }
        }
        root.red = false;
    }

    private void fixDelete(Node x) {
        while (x != root && !x.red) {
            Node s = x == x.parent.left ? x.parent.right : x.parent.left;
            if (s.red) {
                s.red = false;
                x.parent.red = true;
                if (x == x.parent.left) {
                    rotateLeft(x.parent);
                    s = x.parent.right;
                } else {
                    rotateRight(x.parent);
                    s = x.parent.left;
                }
            }
            if (!s.right.red && !s.left.red) {
                s.red = true;
                x = x.parent;
            } else {
                if (s.right == null || !s.right.red) {
                    s.left.red = false;
                    s.red = true;
                    rotateRight(s);
                    s = x.parent.right;
                }
                s.red = x.parent.red;
                x.parent.red = false;
                s.right.red = false;
                if (x == root) break;
                rotateLeft(x.parent);
                x = root;
            }
        }
        x.red = false;
    }

    private void rotateLeft(Node x) {
        Node y = x.right;
        x.right = y.left;
        if (y.left != null) y.left.parent = x;
        y.parent = x.parent;
        if (x == root) root = y;
        else if (x == x.parent.left) x.parent.left = y;
        else x.parent.right = y;
        y.left = x;
        x.parent = y;
    }

    private void rotateRight(Node y) {
        Node x = y.left;
        y.left = x.right;
        if (x.right != null) x.right.parent = y;
        x.parent = y.parent;
        if (y == root) root = x;
        else if (y == y.parent.right) y.parent.right = x;
        else y.parent.left = x;
        x.right = y;
        y.parent = x;
    }

    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException();
        Node z = new Node(key, value);
        if (root == null) {
            root = z;
            size++;
            return null;
        }
        Node y = null, x = root;
        while (x != null) {
            y = x;
            if (order.compare(z.key, x.key) < 0) x = x.left;
            else x = x.right;
        }
        z.parent = y;
        if (y == null) root = z; // new root
        else if (order.compare(z.key, y.key) < 0) y.left = z;
        else y.right = z;
        z.left = null;
        z.right = null;
        z.red = true;
        size++;
        fixInsert(z);
        return null; // Value is not changed in this implementation
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException();
        Node x = root;
        while (x != null) {
            int cmp = order.compare(key, x.key);
            if (cmp < 0) x = x.left;
            else if (cmp > 0) x = x.right;
            else return x.value;
        }
        return null;
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException();
        Node z = root;
        while (z != null) {
            int cmp = order.compare(key, z.key);
            if (cmp < 0) z = z.left;
            else if (cmp > 0) z = z.right;
            else break;
        }
        if (z == null) return null;
        V oldValue = z.value;
        Node x, y;
        if (z.left == null) {
            y = z.right;
        } else if (z.right == null) {
            y = z.left;
        } else {
            Node successor = minimum(z.right);
            if (successor.parent != z) {
                y = successor.parent;
                transplant(y, successor.right);
                successor.right = z.right;
                successor.right.parent = y;
            }
            transplant(z, successor);
            successor.left = z.left;
            successor.left.parent = y;
            successor.red = z.red;
        }
        if (y != null) fixDelete(y);
        size--;
        return oldValue;
    }

    private void transplant(Node u, Node v) {
        if (u.parent == null) root = v;
        else if (u == u.parent.left) u.parent.left = v;
        else u.parent.right = v;
        if (v != null) v.parent = u.parent;
    }

    private Node minimum(Node x) {
        while (x.left != null) x = x.left;
        return x;
    }

    public boolean containsKey(K key) {
        if (key == null) throw new NullPointerException();
        Node x = root;
        while (x != null) {
            int cmp = order.compare(key, x.key);
            if (cmp < 0) x = x.left;
            else if (cmp > 0) x = x.right;
            else return true;
        }
        return false;
    }

    public int size() {
        return size;
    }

    public Entry<K, V> entryAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        Node x = root;
        int rank = 0;
        while (true) {
            if (x.left != null && x.left.count() > index) x = x.left;
            else {
                index -= (x.left != null ? x.left.count() : 0) + 1;
                if (index == rank) break;
                index--;
                x = x.right;
            }
        }
        return new Entry<>(x.key, x.value);
    }

    public int rank(K key) {
        if (key == null) throw new NullPointerException();
        Node x = root;
        int rank = 0;
        while (x != null) {
            int cmp = order.compare(key, x.key);
            if (cmp < 0) x = x.left;
            else if (cmp > 0) {
                rank += (x.left != null ? x.left.count() : 0) + 1;
                x = x.right;
            } else break;
        }
        return rank;
    }

    public Snapshot<K, V> snapshot() {
        return new Snapshot<>(this);
    }
}
