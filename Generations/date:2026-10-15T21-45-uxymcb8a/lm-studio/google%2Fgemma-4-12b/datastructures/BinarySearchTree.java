@BinarySearchTree.ThreadSafe
public class BinarySearchTree<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static class Node<K_Type, V_Type> {
        K_Type key;
        V_Type value;
        Node<K_Type, V_Type> left;
        Node<K_Type, V_Type> right;

        Node(K_Type key, V_Type value) {
            this.key = key;
            this.value = value;
        }
    }

    private Node<K, V> root;
    private final Object lock = new Object();

    public void put(K key, V value) {
        if (key == null) {
            return;
        }
        synchronized (lock) {
            root = insert(root, key, value);
        }
    }

    private Node<K, V> insert(Node<K, V> node, K key, V value) {
        if (node == null) {
            return new Node<>(key, value);
        }

        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            node.left = insert(node.left, key, value);
        } else if (cmp > 0) {
            node.right = insert(node.right, key, value);
        } else {
            node.value = value;
        }
        return node;
    }

    public V get(K key) {
        if (key == null) {
            return null;
        }
        synchronized (lock) {
            Node<K, V> node = find(root, key);
            return (node != null) ? node.value : null;
        }
    }

    private Node<K, V> find(Node<K, V> node, K key) {
        while (node != null) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return node;
            } else if (cmp < 0) {
                node = node.left;
            } else {
                node = node.right;
            }
        }
        return null;
    }

    public void remove(K key) {
        if (key == null) {
            return;
        }
        synchronized (lock) {
            root = delete(root, key);
        }
    }

    private Node<K, V> delete(Node<K, V> node, K key) {
        if (node == null) {
            return null;
        }

        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            node.left = delete(node.left, key);
        } else if (cmp > 0) {
            node.right = delete(node.right, key);
        } else {
            if (node.left == null) {
                return node.right;
            }
            if (node.right == null) {
                return node.left;
            }

            Node<K, V> minNode = findMin(node.right);
            node.key = minNode.key;
            node.value = minNode.value;
            node.right = deleteMin(node.right);
        }
        return node;
    }

    private Node<K, V> deleteMin(Node<K, V> node) {
        if (node == null) {
            return null;
        }
        if (node.left == null) {
            return node.right;
        }
        node.left = deleteMin(node.left);
        return node;
    }

    private Node<K, V> findMin(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }
}
