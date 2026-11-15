import java.io.Serializable;

interface Order<K> {
    int compare(K left, K right);
}

@Entry.ThreadSafe
class Entry<K, V> implements Serializable {
    @interface ThreadSafe {}

    private final K key;
    private final V value;

    Entry(K key, V value) {
        this.key = key;
        this.value = value;
    }

    K key() {
        return key;
    }

    V value() {
        return value;
    }
}

@Snapshot.ThreadSafe
class Snapshot<K, V> implements Serializable {
    @interface ThreadSafe {}

    private final TreeDictionary<K, V> dictionary;

    Snapshot(TreeDictionary<K, V> dictionary) {
        this.dictionary = dictionary;
    }

    int size() {
        return dictionary.size();
    }

    V get(K key) {
        return dictionary.get(key);
    }

    Entry<K, V> entryAt(int index) {
        return dictionary.entryAt(index);
    }
}

@RedBlackTree.ThreadSafe
class RedBlackTree<K, V> implements Serializable {
    @interface ThreadSafe {}

    private transient Node root;
    private final Order<K> order;

    private static class Node<K, V> implements Serializable {
        K key;
        V value;
        char color; // 'R' for red, 'B' for black
        Node<K, V> left, right;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            color = 'R';
        }
    }

    RedBlackTree(Order<K> order) {
        this.order = order;
    }

    private boolean isRed(Node node) {
        return node != null && node.color == 'R';
    }

    private void flipColors(Node node) {
        if (node != null && node.left != null && node.right != null) {
            node.color = 'R';
            node.left.color = 'B';
            node.right.color = 'B';
        }
    }

    private Node rotateLeft(Node h) {
        Node x = h.right;
        h.right = x.left;
        x.left = h;
        x.color = h.color;
        h.color = 'R';
        return x;
    }

    private Node rotateRight(Node h) {
        Node x = h.left;
        h.left = x.right;
        x.right = h;
        x.color = h.color;
        h.color = 'R';
        return x;
    }

    private Node balance(Node h) {
        if (h == null) return null;

        if (isRed(h.right) && !isRed(h.left)) h = rotateLeft(h);
        if (isRed(h.left) && isRed(h.left.left)) h = rotateRight(h);
        if (isRed(h.left) && isRed(h.right)) flipColors(h);

        h.size = 1 + size(h.left) + size(h.right);
        return h;
    }

    private int size(Node node) {
        if (node == null) return 0;
        return node.size;
    }

    private int rank(K key, Node<K, V> node) {
        if (node == null) return 0;
        int cmp = order.compare(key, node.key);
        if (cmp < 0) return rank(key, node.left);
        else if (cmp > 0) return 1 + size(node.left) + rank(key, node.right);
        else return size(node.left);
    }

    private Node put(K key, V value, Node<K, V> node) {
        if (node == null) return new Node<>(key, value);

        int cmp = order.compare(key, node.key);
        if (cmp < 0) node.left = put(key, value, node.left);
        else if (cmp > 0) node.right = put(key, value, node.right);
        else {
            V oldValue = node.value;
            node.value = value;
            return new Node<>(key, oldValue);
        }

        return balance(node);
    }

    private V get(K key, Node<K, V> node) {
        if (node == null) return null;
        int cmp = order.compare(key, node.key);
        if (cmp < 0) return get(key, node.left);
        else if (cmp > 0) return get(key, node.right);
        else return node.value;
    }

    private Node delete(K key, Node<K, V> node) {
        if (node == null) return null;

        int cmp = order.compare(key, node.key);
        if (cmp < 0) node.left = delete(key, node.left);
        else if (cmp > 0) node.right = delete(key, node.right);
        else {
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;

            Node x = min(node.right);
            node.key = x.key;
            node.value = x.value;
            node.right = deleteMin(node.right);
        }

        return balance(node);
    }

    private Node deleteMin(Node node) {
        if (node.left == null) return null;
        if (!isRed(node.left) && !isRed(node.left.left)) node = moveRedLeft(node);
        node.left = deleteMin(node.left);
        return balance(node);
    }

    private Node moveRedLeft(Node node) {
        if (isRed(node.right.left)) {
            node.right = rotateRight(node.right);
            node = rotateLeft(node);
            flipColors(node);
        }
        return node;
    }

    private Node min(Node node) {
        if (node.left == null) return node;
        return min(node.left);
    }

    public boolean containsKey(K key) {
        return get(key, root) != null;
    }

    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException();
        root = put(key, value, root);
        root.color = 'B';
        return null;
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException();
        return get(key, root);
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException();
        V value = get(key, root);
        root = delete(key, root);
        if (root != null) root.color = 'B';
        return value;
    }

    public int size() {
        if (root == null) return 0;
        return size(root);
    }

    public Entry<K, V> entryAt(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
        return entryAt(root, index);
    }

    private Entry<K, V> entryAt(Node<K, V> node, int index) {
        int leftSize = size(node.left);
        if (leftSize > index) return entryAt(node.left, index);
        else if (leftSize < index) return entryAt(node.right, index - leftSize - 1);
        else return new Entry<>(node.key, node.value);
    }

    public int rank(K key) {
        if (key == null) throw new NullPointerException();
        return rank(key, root);
    }
}

@TreeDictionary.ThreadSafe
public class TreeDictionary<K, V> implements Serializable {
    @interface ThreadSafe {}

    private transient Order<K> order;
    private transient RedBlackTree<K, V> redBlackTree;

    public TreeDictionary(Order<K> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
        redBlackTree = new RedBlackTree<>(order);
    }

    public V put(K key, V value) {
        return redBlackTree.put(key, value);
    }

    public V get(K key) {
        return redBlackTree.get(key);
    }

    public V remove(K key) {
        return redBlackTree.remove(key);
    }

    public boolean containsKey(K key) {
        return redBlackTree.containsKey(key);
    }

    public int size() {
        return redBlackTree.size();
    }

    public Entry<K, V> entryAt(int index) {
        return redBlackTree.entryAt(index);
    }

    public int rank(K key) {
        return redBlackTree.rank(key);
    }

    public Snapshot<K, V> snapshot() {
        return new Snapshot<>(this);
    }
}
