import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

interface Order<K> {
    int compare(K left, K right);
}

@TreeDictionary.ThreadSafe
class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private final Order<K> order;
    private Node<K, V> root = null;
    private int size = 0;

    TreeDictionary(Order<K> order) {
        if (order == null) throw new NullPointerException("Order cannot be null");
        this.order = order;
    }

    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("Key and value cannot be null");
        Lock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        Node<K, V> newNode = new Node<>(key, value);
        try {
            lock.lock();
            if (root == null) {
                root = newNode;
                size++;
                return null;
            } else {
                Node<K, V> current = root;
                while (true) {
                    int cmp = order.compare(key, current.key);
                    if (cmp == 0) {
                        V oldValue = current.value;
                        current.value = value;
                        return oldValue;
                    } else if (cmp < 0) {
                        if (current.left == null) {
                            current.left = newNode;
                            size++;
                            return null;
                        } else {
                            current = current.left;
                        }
                    } else {
                        if (current.right == null) {
                            current.right = newNode;
                            size++;
                            return null;
                        } else {
                            current = current.right;
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        Lock lock = new ReentrantLock();
        try {
            lock.lock();
            Node<K, V> current = root;
            while (current != null) {
                int cmp = order.compare(key, current.key);
                if (cmp == 0) return current.value;
                else if (cmp < 0) current = current.left;
                else current = current.right;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        Lock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        try {
            lock.lock();
            Node<K, V> current = root;
            while (current != null) {
                int cmp = order.compare(key, current.key);
                if (cmp == 0) {
                    V oldValue = current.value;
                    root = removeNode(root, key);
                    size--;
                    return oldValue;
                } else if (cmp < 0) current = current.left;
                else current = current.right;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private Node<K, V> removeNode(Node<K, V> node, K key) {
        if (node == null) return null;
        int cmp = order.compare(key, node.key);
        if (cmp < 0) {
            node.left = removeNode(node.left, key);
        } else if (cmp > 0) {
            node.right = removeNode(node.right, key);
        } else {
            if (node.left == null) return node.right;
            else if (node.right == null) return node.left;
            else {
                Node<K, V> successor = findMin(node.right);
                node.key = successor.key;
                node.value = successor.value;
                node.right = removeNode(node.right, successor.key);
            }
        }
        return node;
    }

    private Node<K, V> findMin(Node<K, V> node) {
        while (node.left != null) node = node.left;
        return node;
    }

    public boolean containsKey(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        Lock lock = new ReentrantLock();
        try {
            lock.lock();
            Node<K, V> current = root;
            while (current != null) {
                int cmp = order.compare(key, current.key);
                if (cmp == 0) return true;
                else if (cmp < 0) current = current.left;
                else current = current.right;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return size;
    }

    public Entry<K, V> entryAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index out of bounds");
        Lock lock = new ReentrantLock();
        try {
            lock.lock();
            Node<K, V> current = root;
            int count = 0;
            while (true) {
                if (current.left != null && current.left.count > index - count) {
                    current = current.left;
                } else {
                    count += (current.left != null ? current.left.count : 0) + 1;
                    if (count == index + 1) return new ImmutableEntry<>(current.key, current.value);
                    else if (count > index + 1) {
                        current = current.right;
                    } else break;
                }
            }
        } finally {
            lock.unlock();
        }
        return null;
    }

    public int rank(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        Lock lock = new ReentrantLock();
        try {
            lock.lock();
            Node<K, V> current = root;
            int rank = 0;
            while (current != null) {
                int cmp = order.compare(key, current.key);
                if (cmp < 0) {
                    current = current.left;
                } else if (cmp > 0) {
                    rank += (current.left != null ? current.left.count : 0) + 1;
                    current = current.right;
                } else {
                    rank += (current.left != null ? current.left.count : 0);
                    return rank;
                }
            }
            return rank;
        } finally {
            lock.unlock();
        }
    }

    public Snapshot<K, V> snapshot() {
        Lock lock = new ReentrantLock();
        try {
            lock.lock();
            return new Snapshot<>(root, size);
        } finally {
            lock.unlock();
        }
    }

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> left = null, right = null;
        int count = 1;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    public static class Entry<K, V> {
        private final K key;
        private final V value;

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

    public static class Snapshot<K, V> {
        private final Node<K, V> root;
        private final int size;

        public Snapshot(Node<K, V> root, int size) {
            this.root = root;
            this.size = size;
        }

        public int size() {
            return size;
        }

        public V get(K key) {
            Node<K, V> current = root;
            while (current != null) {
                int cmp = order.compare(key, current.key);
                if (cmp == 0) return current.value;
                else if (cmp < 0) current = current.left;
                else current = current.right;
            }
            return null;
        }

        public Entry<K, V> entryAt(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index out of bounds");
            Node<K, V> current = root;
            int count = 0;
            while (true) {
                if (current.left != null && current.left.count > index - count) {
                    current = current.left;
                } else {
                    count += (current.left != null ? current.left.count : 0) + 1;
                    if (count == index + 1) return new ImmutableEntry<>(current.key, current.value);
                    else if (count > index + 1) {
                        current = current.right;
                    } else break;
                }
            }
            return null;
        }

        private static class ImmutableEntry<K, V> implements Entry<K, V> {
            private final K key;
            private final V value;

            public ImmutableEntry(K key, V value) {
                this.key = key;
                this.value = value;
            }

            @Override
            public K key() {
                return key;
            }

            @Override
            public V value() {
                return value;
            }
        }
    }
}
