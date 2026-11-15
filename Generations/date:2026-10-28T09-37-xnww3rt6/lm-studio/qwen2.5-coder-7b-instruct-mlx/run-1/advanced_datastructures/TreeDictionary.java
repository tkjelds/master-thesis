import java.util.NoSuchElementException;

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

    public K key() {
        return key;
    }

    public V value() {
        return value;
    }
}

@Snapshot.ThreadSafe
class Snapshot<K, V> {
    @interface ThreadSafe {}

    private final TreeDictionary<K, V> dictionary;

    public Snapshot(TreeDictionary<K, V> dictionary) {
        this.dictionary = new TreeDictionary<>(dictionary.order);
    }

    public int size() {
        return dictionary.size();
    }

    public V get(K key) {
        return dictionary.get(key);
    }

    public Entry<K, V> entryAt(int index) {
        return dictionary.entryAt(index);
    }
}

@TreeDictionary.ThreadSafe
class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private final Order<K> order;
    private Node<K, V> root;
    private int size;

    public TreeDictionary(Order<K> order) {
        if (order == null) {
            throw new NullPointerException("Order cannot be null");
        }
        this.order = order;
        this.root = null;
        this.size = 0;
    }

    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }
        root = put(root, key, value);
        return null;
    }

    private Node<K, V> put(Node<K, V> node, K key, V value) {
        if (node == null) {
            size++;
            return new Node<>(key, value);
        }
        int cmp = order.compare(key, node.key);
        if (cmp < 0) {
            node.left = put(node.left, key, value);
        } else if (cmp > 0) {
            node.right = put(node.right, key, value);
        } else {
            V oldValue = node.value;
            node.value = value;
            return node;
        }
        balance(node);
        return node;
    }

    private Node<K, V> balance(Node<K, V> node) {
        // Implement red-black tree balancing logic here
        return null;
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return get(root, key);
    }

    private V get(Node<K, V> node, K key) {
        if (node == null) {
            return null;
        }
        int cmp = order.compare(key, node.key);
        if (cmp < 0) {
            return get(node.left, key);
        } else if (cmp > 0) {
            return get(node.right, key);
        } else {
            return node.value;
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        V value = get(key);
        if (value != null) {
            root = remove(root, key);
            size--;
        }
        return value;
    }

    private Node<K, V> remove(Node<K, V> node, K key) {
        // Implement red-black tree deletion logic here
        return null;
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return get(key) != null;
    }

    public int size() {
        return size;
    }

    public Entry<K, V> entryAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index out of bounds");
        }
        return entryAt(root, index);
    }

    private Entry<K, V> entryAt(Node<K, V> node, int index) {
        if (node == null) {
            return null;
        }
        int leftSize = size(node.left);
        if (index < leftSize) {
            return entryAt(node.left, index);
        } else if (index == leftSize) {
            return new Entry<>(node.key, node.value);
        } else {
            return entryAt(node.right, index - leftSize - 1);
        }
    }

    private int rank(K key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        return rank(root, key);
    }

    private int rank(Node<K, V> node, K key) {
        if (node == null) {
            return 0;
        }
        int cmp = order.compare(key, node.key);
        if (cmp < 0) {
            return rank(node.left, key);
        } else if (cmp > 0) {
            return size(node.left) + 1 + rank(node.right, key);
        } else {
            return size(node.left);
        }
    }

    private int size(Node<K, V> node) {
        if (node == null) {
            return 0;
        }
        return size(node.left) + size(node.right) + 1;
    }

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> left;
        Node<K, V> right;

        public Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.left = null;
            this.right = null;
        }
    }
}
