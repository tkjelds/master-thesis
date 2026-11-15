/*
 * Policy for operations that cannot complete immediately:
 *
 * 1. Bounded vs. Unbounded Capacity:
 *    - An instance can be constructed with an explicit positive capacity limit,
 *      or default to unbounded (capacity = Integer.MAX_VALUE).
 *    - When the tree is bounded and full (size >= capacity), inserting a new key
 *      cannot proceed immediately:
 *      * put(key, value) and putIfAbsent(key, value) block using wait() in a while
 *        loop until capacity is freed by a removal or clear operation.
 *      * If the key already exists in the tree, updating its associated value
 *        does not increase the size of the tree and completes immediately
 *        without blocking.
 *      * Non-blocking tryPut(key, value) returns false immediately when full
 *        and the key is not already present.
 *      * Timed tryPut(key, value, timeoutMillis) waits up to the specified
 *        duration using wait(remaining) inside a while loop, returning false
 *        if capacity does not become available.
 *
 * 2. Empty Structure:
 *    - When the tree is empty (size == 0):
 *      * Non-blocking retrieval methods (get, containsKey, remove, pollMin,
 *        pollMax, peekMin, peekMax, firstKey, lastKey) return null or false
 *        immediately.
 *      * Blocking retrieval methods (takeMin, takeMax, take) block using wait()
 *        in a while loop until an element is inserted into the tree.
 *
 * 3. Synchronization and Memory Visibility:
 *    - All public operations synchronize on the tree instance's intrinsic monitor
 *      using synchronized (this) blocks inside method bodies. Method declarations
 *      do not use the synchronized modifier.
 *    - State modifications that decrease size notify waiting producers using notifyAll().
 *    - State modifications that increase size notify waiting consumers using notifyAll().
 *    - Mutable state fields (root, size) are marked volatile, and invariant configuration
 *      fields (capacity, comparator) are declared final to ensure safe publication and
 *      prevent visibility and ordering anomalies across threads.
 *    - No busy-waiting or thread sleeping is used.
 */

@RedBlackTree.ThreadSafe
public class RedBlackTree<K extends Comparable<? super K>, V> {

    @interface ThreadSafe {}

    public interface Comparator<T> {
        int compare(T o1, T o2);
    }

    public static final class Entry<K, V> {
        private final K key;
        private final V value;

        public Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Entry<?, ?>)) {
                return false;
            }
            Entry<?, ?> other = (Entry<?, ?>) obj;
            boolean keyEqual = (key == null ? other.key == null : key.equals(other.key));
            boolean valEqual = (value == null ? other.value == null : value.equals(other.value));
            return keyEqual && valEqual;
        }

        @Override
        public int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }
    }

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private static final class Node<K, V> {
        private K key;
        private V value;
        private Node<K, V> left;
        private Node<K, V> right;
        private Node<K, V> parent;
        private boolean color;

        private Node(K key, V value, boolean color, Node<K, V> parent) {
            this.key = key;
            this.value = value;
            this.color = color;
            this.parent = parent;
        }
    }

    private final int capacity;
    private final Comparator<? super K> comparator;
    private volatile Node<K, V> root;
    private volatile int size;

    public RedBlackTree() {
        this(Integer.MAX_VALUE, null);
    }

    public RedBlackTree(int capacity) {
        this(capacity, null);
    }

    public RedBlackTree(Comparator<? super K> comparator) {
        this(Integer.MAX_VALUE, comparator);
    }

    public RedBlackTree(int capacity, Comparator<? super K> comparator) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.comparator = comparator;
        this.root = null;
        this.size = 0;
    }

    public V put(K key, V value) throws InterruptedException {
        checkKey(key);
        synchronized (this) {
            while (true) {
                Node<K, V> existing = findNode(key);
                if (existing != null) {
                    V oldValue = existing.value;
                    existing.value = value;
                    return oldValue;
                }
                if (size < capacity) {
                    insertNewNode(key, value);
                    notifyAll();
                    return null;
                }
                wait();
            }
        }
    }

    public V putIfAbsent(K key, V value) throws InterruptedException {
        checkKey(key);
        synchronized (this) {
            while (true) {
                Node<K, V> existing = findNode(key);
                if (existing != null) {
                    return existing.value;
                }
                if (size < capacity) {
                    insertNewNode(key, value);
                    notifyAll();
                    return null;
                }
                wait();
            }
        }
    }

    public boolean tryPut(K key, V value) {
        checkKey(key);
        synchronized (this) {
            Node<K, V> existing = findNode(key);
            if (existing != null) {
                existing.value = value;
                return true;
            }
            if (size >= capacity) {
                return false;
            }
            insertNewNode(key, value);
            notifyAll();
            return true;
        }
    }

    public boolean tryPut(K key, V value, long timeoutMillis) throws InterruptedException {
        checkKey(key);
        synchronized (this) {
            if (timeoutMillis <= 0) {
                return tryPut(key, value);
            }
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (true) {
                Node<K, V> existing = findNode(key);
                if (existing != null) {
                    existing.value = value;
                    return true;
                }
                if (size < capacity) {
                    insertNewNode(key, value);
                    notifyAll();
                    return true;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                wait(remaining);
            }
        }
    }

    public V get(K key) {
        checkKey(key);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    public V getOrDefault(K key, V defaultValue) {
        checkKey(key);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            return node == null ? defaultValue : node.value;
        }
    }

    public boolean containsKey(K key) {
        checkKey(key);
        synchronized (this) {
            return findNode(key) != null;
        }
    }

    public boolean containsValue(V value) {
        synchronized (this) {
            return containsValueHelper(root, value);
        }
    }

    public V remove(K key) {
        checkKey(key);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            if (node == null) {
                return null;
            }
            V oldValue = node.value;
            deleteNode(node);
            notifyAll();
            return oldValue;
        }
    }

    public boolean remove(K key, V value) {
        checkKey(key);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            if (node == null || !equalsValue(node.value, value)) {
                return false;
            }
            deleteNode(node);
            notifyAll();
            return true;
        }
    }

    public V replace(K key, V value) {
        checkKey(key);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            if (node == null) {
                return null;
            }
            V oldValue = node.value;
            node.value = value;
            return oldValue;
        }
    }

    public boolean replace(K key, V oldValue, V newValue) {
        checkKey(key);
        synchronized (this) {
            Node<K, V> node = findNode(key);
            if (node == null || !equalsValue(node.value, oldValue)) {
                return false;
            }
            node.value = newValue;
            return true;
        }
    }

    public Entry<K, V> takeMin() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Node<K, V> min = minimum(root);
            Entry<K, V> entry = new Entry<K, V>(min.key, min.value);
            deleteNode(min);
            notifyAll();
            return entry;
        }
    }

    public Entry<K, V> takeMax() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Node<K, V> max = maximum(root);
            Entry<K, V> entry = new Entry<K, V>(max.key, max.value);
            deleteNode(max);
            notifyAll();
            return entry;
        }
    }

    public Entry<K, V> take() throws InterruptedException {
        return takeMin();
    }

    public Entry<K, V> pollMin() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            Node<K, V> min = minimum(root);
            Entry<K, V> entry = new Entry<K, V>(min.key, min.value);
            deleteNode(min);
            notifyAll();
            return entry;
        }
    }

    public Entry<K, V> pollMax() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            Node<K, V> max = maximum(root);
            Entry<K, V> entry = new Entry<K, V>(max.key, max.value);
            deleteNode(max);
            notifyAll();
            return entry;
        }
    }

    public Entry<K, V> poll() {
        return pollMin();
    }

    public Entry<K, V> peekMin() {
        synchronized (this) {
            if (root == null) {
                return null;
            }
            Node<K, V> min = minimum(root);
            return new Entry<K, V>(min.key, min.value);
        }
    }

    public Entry<K, V> peekMax() {
        synchronized (this) {
            if (root == null) {
                return null;
            }
            Node<K, V> max = maximum(root);
            return new Entry<K, V>(max.key, max.value);
        }
    }

    public Entry<K, V> peek() {
        return peekMin();
    }

    public Entry<K, V> firstEntry() {
        return peekMin();
    }

    public Entry<K, V> lastEntry() {
        return peekMax();
    }

    public K firstKey() {
        synchronized (this) {
            return root == null ? null : minimum(root).key;
        }
    }

    public K lastKey() {
        synchronized (this) {
            return root == null ? null : maximum(root).key;
        }
    }

    public V firstValue() {
        synchronized (this) {
            return root == null ? null : minimum(root).value;
        }
    }

    public V lastValue() {
        synchronized (this) {
            return root == null ? null : maximum(root).value;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return size >= capacity;
        }
    }

    public int getCapacity() {
        synchronized (this) {
            return capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (this) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (this) {
            root = null;
            size = 0;
            notifyAll();
        }
    }

    public int height() {
        synchronized (this) {
            return computeHeight(root);
        }
    }

    public Object[] toArray() {
        synchronized (this) {
            Object[] array = new Object[size];
            int[] index = new int[1];
            inOrderCollect(root, array, index);
            return array;
        }
    }

    private void inOrderCollect(Node<K, V> node, Object[] array, int[] index) {
        if (node != null) {
            inOrderCollect(node.left, array, index);
            array[index[0]++] = new Entry<K, V>(node.key, node.value);
            inOrderCollect(node.right, array, index);
        }
    }

    private void checkKey(K key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
    }

    private int compare(K k1, K k2) {
        if (comparator != null) {
            return comparator.compare(k1, k2);
        }
        return k1.compareTo(k2);
    }

    private boolean equalsValue(V v1, V v2) {
        return v1 == v2 || (v1 != null && v1.equals(v2));
    }

    private boolean containsValueHelper(Node<K, V> node, V value) {
        if (node == null) {
            return false;
        }
        if (equalsValue(node.value, value)) {
            return true;
        }
        return containsValueHelper(node.left, value) || containsValueHelper(node.right, value);
    }

    private int computeHeight(Node<K, V> node) {
        if (node == null) {
            return 0;
        }
        int leftH = computeHeight(node.left);
        int rightH = computeHeight(node.right);
        return 1 + (leftH > rightH ? leftH : rightH);
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> current = root;
        while (current != null) {
            int cmp = compare(key, current.key);
            if (cmp == 0) {
                return current;
            } else if (cmp < 0) {
                current = current.left;
            } else {
                current = current.right;
            }
        }
        return null;
    }

    private Node<K, V> minimum(Node<K, V> node) {
        Node<K, V> current = node;
        while (current != null && current.left != null) {
            current = current.left;
        }
        return current;
    }

    private Node<K, V> maximum(Node<K, V> node) {
        Node<K, V> current = node;
        while (current != null && current.right != null) {
            current = current.right;
        }
        return current;
    }

    private void insertNewNode(K key, V value) {
        if (root == null) {
            root = new Node<K, V>(key, value, BLACK, null);
            size = 1;
            return;
        }

        Node<K, V> current = root;
        Node<K, V> parent = null;
        int cmp = 0;

        while (current != null) {
            parent = current;
            cmp = compare(key, current.key);
            if (cmp == 0) {
                current.value = value;
                return;
            } else if (cmp < 0) {
                current = current.left;
            } else {
                current = current.right;
            }
        }

        Node<K, V> newNode = new Node<K, V>(key, value, RED, parent);
        if (cmp < 0) {
            parent.left = newNode;
        } else {
            parent.right = newNode;
        }

        size++;
        fixAfterInsertion(newNode);
    }

    private void deleteNode(Node<K, V> node) {
        Node<K, V> replacement;
        Node<K, V> replacementParent;
        Node<K, V> successor = node;
        boolean successorColor = successor.color;

        if (node.left == null) {
            replacement = node.right;
            replacementParent = node.parent;
            transplant(node, node.right);
        } else if (node.right == null) {
            replacement = node.left;
            replacementParent = node.parent;
            transplant(node, node.left);
        } else {
            successor = minimum(node.right);
            successorColor = successor.color;
            replacement = successor.right;

            if (successor.parent == node) {
                replacementParent = successor;
                if (replacement != null) {
                    replacement.parent = successor;
                }
            } else {
                replacementParent = successor.parent;
                transplant(successor, successor.right);
                successor.right = node.right;
                successor.right.parent = successor;
            }

            transplant(node, successor);
            successor.left = node.left;
            successor.left.parent = successor;
            successor.color = node.color;
        }

        size--;

        if (successorColor == BLACK) {
            fixAfterDeletion(replacement, replacementParent);
        }

        node.left = null;
        node.right = null;
        node.parent = null;
    }

    private void fixAfterInsertion(Node<K, V> node) {
        node.color = RED;

        while (node != null && node != root && colorOf(parentOf(node)) == RED) {
            Node<K, V> parent = parentOf(node);
            Node<K, V> grandparent = parentOf(parent);

            if (parent == leftOf(grandparent)) {
                Node<K, V> uncle = rightOf(grandparent);

                if (colorOf(uncle) == RED) {
                    setColor(parent, BLACK);
                    setColor(uncle, BLACK);
                    setColor(grandparent, RED);
                    node = grandparent;
                } else {
                    if (node == rightOf(parent)) {
                        node = parent;
                        rotateLeft(node);
                        parent = parentOf(node);
                        grandparent = parentOf(parent);
                    }
                    setColor(parent, BLACK);
                    setColor(grandparent, RED);
                    rotateRight(grandparent);
                }
            } else {
                Node<K, V> uncle = leftOf(grandparent);

                if (colorOf(uncle) == RED) {
                    setColor(parent, BLACK);
                    setColor(uncle, BLACK);
                    setColor(grandparent, RED);
                    node = grandparent;
                } else {
                    if (node == leftOf(parent)) {
                        node = parent;
                        rotateRight(node);
                        parent = parentOf(node);
                        grandparent = parentOf(parent);
                    }
                    setColor(parent, BLACK);
                    setColor(grandparent, RED);
                    rotateLeft(grandparent);
                }
            }
        }

        setColor(root, BLACK);
    }

    private void fixAfterDeletion(Node<K, V> node, Node<K, V> parent) {
        while (node != root && colorOf(node) == BLACK) {
            if (parent == null) {
                break;
            }

            if (node == parent.left) {
                Node<K, V> sibling = parent.right;

                if (colorOf(sibling) == RED) {
                    setColor(sibling, BLACK);
                    setColor(parent, RED);
                    rotateLeft(parent);
                    sibling = parent.right;
                }

                if (colorOf(leftOf(sibling)) == BLACK && colorOf(rightOf(sibling)) == BLACK) {
                    setColor(sibling, RED);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (colorOf(rightOf(sibling)) == BLACK) {
                        setColor(leftOf(sibling), BLACK);
                        setColor(sibling, RED);
                        rotateRight(sibling);
                        sibling = parent.right;
                    }

                    setColor(sibling, colorOf(parent));
                    setColor(parent, BLACK);
                    setColor(rightOf(sibling), BLACK);
                    rotateLeft(parent);
                    node = root;
                    parent = null;
                }
            } else {
                Node<K, V> sibling = parent.left;

                if (colorOf(sibling) == RED) {
                    setColor(sibling, BLACK);
                    setColor(parent, RED);
                    rotateRight(parent);
                    sibling = parent.left;
                }

                if (colorOf(rightOf(sibling)) == BLACK && colorOf(leftOf(sibling)) == BLACK) {
                    setColor(sibling, RED);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (colorOf(leftOf(sibling)) == BLACK) {
                        setColor(rightOf(sibling), BLACK);
                        setColor(sibling, RED);
                        rotateLeft(sibling);
                        sibling = parent.left;
                    }

                    setColor(sibling, colorOf(parent));
                    setColor(parent, BLACK);
                    setColor(leftOf(sibling), BLACK);
                    rotateRight(parent);
                    node = root;
                    parent = null;
                }
            }
        }

        setColor(node, BLACK);
    }

    private void transplant(Node<K, V> oldNode, Node<K, V> newNode) {
        if (oldNode.parent == null) {
            root = newNode;
        } else if (oldNode == oldNode.parent.left) {
            oldNode.parent.left = newNode;
        } else {
            oldNode.parent.right = newNode;
        }

        if (newNode != null) {
            newNode.parent = oldNode.parent;
        }
    }

    private void rotateLeft(Node<K, V> node) {
        if (node == null) {
            return;
        }

        Node<K, V> right = node.right;
        node.right = right.left;

        if (right.left != null) {
            right.left.parent = node;
        }

        right.parent = node.parent;

        if (node.parent == null) {
            root = right;
        } else if (node == node.parent.left) {
            node.parent.left = right;
        } else {
            node.parent.right = right;
        }

        right.left = node;
        node.parent = right;
    }

    private void rotateRight(Node<K, V> node) {
        if (node == null) {
            return;
        }

        Node<K, V> left = node.left;
        node.left = left.right;

        if (left.right != null) {
            left.right.parent = node;
        }

        left.parent = node.parent;

        if (node.parent == null) {
            root = left;
        } else if (node == node.parent.right) {
            node.parent.right = left;
        } else {
            node.parent.left = left;
        }

        left.right = node;
        node.parent = left;
    }

    private boolean colorOf(Node<K, V> node) {
        return node == null ? BLACK : node.color;
    }

    private void setColor(Node<K, V> node, boolean color) {
        if (node != null) {
            node.color = color;
        }
    }

    private Node<K, V> parentOf(Node<K, V> node) {
        return node == null ? null : node.parent;
    }

    private Node<K, V> leftOf(Node<K, V> node) {
        return node == null ? null : node.left;
    }

    private Node<K, V> rightOf(Node<K, V> node) {
        return node == null ? null : node.right;
    }
}
