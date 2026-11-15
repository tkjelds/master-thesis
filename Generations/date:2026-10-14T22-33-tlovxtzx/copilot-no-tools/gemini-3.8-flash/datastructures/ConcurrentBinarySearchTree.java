@ConcurrentBinarySearchTree.ThreadSafe
public class ConcurrentBinarySearchTree<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    /**
     * Policy for operations that cannot complete immediately:
     *
     * 1. Bounded Capacity (Full Structure):
     *    - The tree can be configured with an optional upper bound on capacity.
     *    - When the tree reaches capacity (size >= capacity), inserting a new unique
     *      key via blocking operations (such as put) will cause the calling thread
     *      to block using intrinsic monitor wait() inside a while loop until capacity
     *      is freed by a removal or extraction operation.
     *    - Updating an existing key's value does not increase size and therefore
     *      proceeds without blocking even when at capacity.
     *    - Non-blocking methods (tryPut) and timed methods (put with timeout)
     *      are provided for non-blocking or bounded-wait use cases.
     *
     * 2. Empty Structure:
     *    - Operations that extract elements from an empty tree (takeMin, takeMax)
     *      block using wait() inside a while loop until at least one entry becomes
     *      available.
     *    - Non-blocking counterparts (pollMin, pollMax, peekMin, peekMax) return null
     *      immediately if the tree is empty.
     *
     * 3. Notification and Visibility:
     *    - Every successful modification that creates space (remove, takeMin, takeMax,
     *      pollMin, pollMax, clear) notifies all waiting threads via lock.notifyAll().
     *    - Every successful insertion that adds an element to the tree invokes
     *      lock.notifyAll() to awaken consumers waiting on an empty tree.
     *    - All public operations synchronize on a private final intrinsic monitor
     *      to ensure mutual exclusion, prevent lost updates, prevent race conditions,
     *      and guarantee memory visibility across threads.
     */

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
    }

    private static final class Node<K, V> {
        K key;
        V value;
        Node<K, V> left;
        Node<K, V> right;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class Holder<T> {
        T value;
        boolean found;
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<K, V> root;
    private int size;

    public ConcurrentBinarySearchTree() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentBinarySearchTree(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be strictly positive");
        }
        this.capacity = capacity;
        this.root = null;
        this.size = 0;
    }

    public V put(K key, V value) throws InterruptedException {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        synchronized (lock) {
            while (size >= capacity && !containsNode(root, key)) {
                lock.wait();
            }
            Holder<V> oldVal = new Holder<V>();
            root = insert(root, key, value, oldVal);
            if (!oldVal.found) {
                size++;
                lock.notifyAll();
            }
            return oldVal.value;
        }
    }

    public boolean tryPut(K key, V value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        synchronized (lock) {
            if (size >= capacity && !containsNode(root, key)) {
                return false;
            }
            Holder<V> oldVal = new Holder<V>();
            root = insert(root, key, value, oldVal);
            if (!oldVal.found) {
                size++;
                lock.notifyAll();
            }
            return true;
        }
    }

    public boolean put(K key, V value, long timeoutMillis) throws InterruptedException {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        synchronized (lock) {
            if (size >= capacity && !containsNode(root, key)) {
                if (timeoutMillis == 0) {
                    return false;
                }
                long deadline = System.currentTimeMillis() + timeoutMillis;
                while (size >= capacity && !containsNode(root, key)) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return false;
                    }
                    lock.wait(remaining);
                }
            }
            Holder<V> oldVal = new Holder<V>();
            root = insert(root, key, value, oldVal);
            if (!oldVal.found) {
                size++;
                lock.notifyAll();
            }
            return true;
        }
    }

    public V get(K key) {
        if (key == null) {
            return null;
        }
        synchronized (lock) {
            Node<K, V> current = root;
            while (current != null) {
                int cmp = key.compareTo(current.key);
                if (cmp < 0) {
                    current = current.left;
                } else if (cmp > 0) {
                    current = current.right;
                } else {
                    return current.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            return containsNode(root, key);
        }
    }

    public V remove(K key) {
        if (key == null) {
            return null;
        }
        synchronized (lock) {
            Holder<V> oldVal = new Holder<V>();
            root = remove(root, key, oldVal);
            if (oldVal.found) {
                size--;
                lock.notifyAll();
            }
            return oldVal.value;
        }
    }

    public Entry<K, V> takeMin() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Node<K, V> min = minNode(root);
            Entry<K, V> entry = new Entry<K, V>(min.key, min.value);
            root = deleteMin(root);
            size--;
            lock.notifyAll();
            return entry;
        }
    }

    public Entry<K, V> takeMax() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Node<K, V> max = maxNode(root);
            Entry<K, V> entry = new Entry<K, V>(max.key, max.value);
            root = deleteMax(root);
            size--;
            lock.notifyAll();
            return entry;
        }
    }

    public Entry<K, V> pollMin() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> min = minNode(root);
            Entry<K, V> entry = new Entry<K, V>(min.key, min.value);
            root = deleteMin(root);
            size--;
            lock.notifyAll();
            return entry;
        }
    }

    public Entry<K, V> pollMax() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> max = maxNode(root);
            Entry<K, V> entry = new Entry<K, V>(max.key, max.value);
            root = deleteMax(root);
            size--;
            lock.notifyAll();
            return entry;
        }
    }

    public Entry<K, V> pollMin(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        synchronized (lock) {
            if (size == 0) {
                if (timeoutMillis == 0) {
                    return null;
                }
                long deadline = System.currentTimeMillis() + timeoutMillis;
                while (size == 0) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return null;
                    }
                    lock.wait(remaining);
                }
            }
            Node<K, V> min = minNode(root);
            Entry<K, V> entry = new Entry<K, V>(min.key, min.value);
            root = deleteMin(root);
            size--;
            lock.notifyAll();
            return entry;
        }
    }

    public Entry<K, V> pollMax(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        synchronized (lock) {
            if (size == 0) {
                if (timeoutMillis == 0) {
                    return null;
                }
                long deadline = System.currentTimeMillis() + timeoutMillis;
                while (size == 0) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return null;
                    }
                    lock.wait(remaining);
                }
            }
            Node<K, V> max = maxNode(root);
            Entry<K, V> entry = new Entry<K, V>(max.key, max.value);
            root = deleteMax(root);
            size--;
            lock.notifyAll();
            return entry;
        }
    }

    public Entry<K, V> peekMin() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> min = minNode(root);
            return new Entry<K, V>(min.key, min.value);
        }
    }

    public Entry<K, V> peekMax() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> max = maxNode(root);
            return new Entry<K, V>(max.key, max.value);
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            root = null;
            size = 0;
            lock.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public Entry<K, V>[] inOrderEntries() {
        synchronized (lock) {
            Entry<K, V>[] result = (Entry<K, V>[]) new Entry[size];
            int[] index = new int[1];
            collectInOrder(root, result, index);
            return result;
        }
    }

    private void collectInOrder(Node<K, V> node, Entry<K, V>[] array, int[] index) {
        if (node != null) {
            collectInOrder(node.left, array, index);
            array[index[0]++] = new Entry<K, V>(node.key, node.value);
            collectInOrder(node.right, array, index);
        }
    }

    private boolean containsNode(Node<K, V> node, K key) {
        while (node != null) {
            int cmp = key.compareTo(node.key);
            if (cmp < 0) {
                node = node.left;
            } else if (cmp > 0) {
                node = node.right;
            } else {
                return true;
            }
        }
        return false;
    }

    private Node<K, V> insert(Node<K, V> node, K key, V value, Holder<V> oldVal) {
        if (node == null) {
            return new Node<K, V>(key, value);
        }
        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            node.left = insert(node.left, key, value, oldVal);
        } else if (cmp > 0) {
            node.right = insert(node.right, key, value, oldVal);
        } else {
            oldVal.found = true;
            oldVal.value = node.value;
            node.value = value;
        }
        return node;
    }

    private Node<K, V> remove(Node<K, V> node, K key, Holder<V> oldVal) {
        if (node == null) {
            return null;
        }
        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            node.left = remove(node.left, key, oldVal);
        } else if (cmp > 0) {
            node.right = remove(node.right, key, oldVal);
        } else {
            oldVal.found = true;
            oldVal.value = node.value;
            if (node.left == null) {
                return node.right;
            }
            if (node.right == null) {
                return node.left;
            }
            Node<K, V> successor = minNode(node.right);
            node.key = successor.key;
            node.value = successor.value;
            node.right = deleteMin(node.right);
        }
        return node;
    }

    private Node<K, V> minNode(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node<K, V> maxNode(Node<K, V> node) {
        while (node.right != null) {
            node = node.right;
        }
        return node;
    }

    private Node<K, V> deleteMin(Node<K, V> node) {
        if (node.left == null) {
            return node.right;
        }
        node.left = deleteMin(node.left);
        return node;
    }

    private Node<K, V> deleteMax(Node<K, V> node) {
        if (node.right == null) {
            return node.left;
        }
        node.right = deleteMax(node.right);
        return node;
    }
}
