@BinarySearchTree.ThreadSafe
public class BinarySearchTree {
    @interface ThreadSafe {}

    private static final int UNBOUNDED = Integer.MAX_VALUE;

    private final Object monitor;
    private volatile Node root;
    private volatile int size;
    private final int capacity;

    // Blocking policy: insert waits while full, and remove waits while empty.
    public BinarySearchTree() {
        this(UNBOUNDED);
    }

    public BinarySearchTree(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be non-negative.");
        }
        this.monitor = new Object();
        this.capacity = capacity;
        this.root = null;
        this.size = 0;
    }

    public boolean add(int value) {
        return insert(value);
    }

    public boolean insert(int value) {
        synchronized (monitor) {
            while (capacity != UNBOUNDED && size >= capacity) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (containsNode(root, value)) {
                return false;
            }

            root = insertNode(root, value);
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean delete(int value) {
        return remove(value);
    }

    public boolean remove(int value) {
        synchronized (monitor) {
            while (root == null) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (!containsNode(root, value)) {
                return false;
            }

            root = removeNode(root, value);
            size--;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean contains(int value) {
        synchronized (monitor) {
            return containsNode(root, value);
        }
    }

    public boolean containsValue(int value) {
        return contains(value);
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return capacity != UNBOUNDED && size >= capacity;
        }
    }

    public int getCapacity() {
        return capacity;
    }

    public void clear() {
        synchronized (monitor) {
            root = null;
            size = 0;
            monitor.notifyAll();
        }
    }

    private Node insertNode(Node node, int value) {
        if (node == null) {
            return new Node(value);
        }

        if (value < node.value) {
            node.left = insertNode(node.left, value);
        } else if (value > node.value) {
            node.right = insertNode(node.right, value);
        }

        return node;
    }

    private boolean containsNode(Node node, int value) {
        while (node != null) {
            if (value < node.value) {
                node = node.left;
            } else if (value > node.value) {
                node = node.right;
            } else {
                return true;
            }
        }
        return false;
    }

    private Node removeNode(Node node, int value) {
        if (node == null) {
            return null;
        }

        if (value < node.value) {
            node.left = removeNode(node.left, value);
            return node;
        }

        if (value > node.value) {
            node.right = removeNode(node.right, value);
            return node;
        }

        if (node.left == null) {
            return node.right;
        }

        if (node.right == null) {
            return node.left;
        }

        Node successor = minimum(node.right);
        node.value = successor.value;
        node.right = deleteMinimum(node.right);
        return node;
    }

    private Node minimum(Node node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node deleteMinimum(Node node) {
        if (node.left == null) {
            return node.right;
        }
        node.left = deleteMinimum(node.left);
        return node;
    }

    private static final class Node {
        private int value;
        private Node left;
        private Node right;

        private Node(int value) {
            this.value = value;
            this.left = null;
            this.right = null;
        }
    }
}
