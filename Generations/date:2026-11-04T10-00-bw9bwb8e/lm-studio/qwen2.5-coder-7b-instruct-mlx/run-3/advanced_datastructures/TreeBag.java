import java.util.AbstractList;
import java.util.NoSuchElementException;

interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
class Snapshot<T> extends AbstractList<T> {
    @interface ThreadSafe {}

    private final TreeBag tree;
    private final Object[] elements;

    Snapshot(TreeBag tree) {
        this.tree = tree;
        this.elements = new Object[tree.size()];
        for (int i = 0; i < elements.length; i++) {
            elements[i] = tree.get(i);
        }
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= elements.length) {
            throw new IndexOutOfBoundsException();
        }
        return (T) elements[index];
    }

    @Override
    public int size() {
        return elements.length;
    }
}

@TreeBag.ThreadSafe
class TreeBag<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private Node root = null;
    private int size = 0;

    TreeBag(Order<T> order) {
        this.order = order;
    }

    synchronized void add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        root = addRecursive(root, item);
    }

    private Node addRecursive(Node node, T item) {
        if (node == null) {
            return new Node(item);
        }
        int cmp = order.compare(node.item, item);
        if (cmp < 0) {
            node.right = addRecursive(node.right, item);
        } else if (cmp > 0) {
            node.left = addRecursive(node.left, item);
        } else {
            node.count++;
        }
        updateSize(node);
        return balance(node);
    }

    synchronized boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        Node node = searchRecursive(root, item);
        if (node == null) {
            return false;
        }
        node.count--;
        if (node.count == 0) {
            root = removeRecursive(root, item);
        }
        updateSize(node);
        return true;
    }

    private Node removeRecursive(Node node, T item) {
        if (node == null) {
            return null;
        }
        int cmp = order.compare(node.item, item);
        if (cmp < 0) {
            node.right = removeRecursive(node.right, item);
        } else if (cmp > 0) {
            node.left = removeRecursive(node.left, item);
        } else {
            if (node.right == null) {
                return node.left;
            } else if (node.left == null) {
                return node.right;
            }
            Node successor = node.right;
            while (successor.left != null) {
                successor = successor.left;
            }
            node.item = successor.item;
            node.count = successor.count;
            node.right = removeRecursive(node.right, successor.item);
        }
        updateSize(node);
        return balance(node);
    }

    private Node searchRecursive(Node node, T item) {
        if (node == null) {
            return null;
        }
        int cmp = order.compare(node.item, item);
        if (cmp < 0) {
            return searchRecursive(node.right, item);
        } else if (cmp > 0) {
            return searchRecursive(node.left, item);
        } else {
            return node;
        }
    }

    private void updateSize(Node node) {
        if (node != null) {
            node.size = 1 + sizeOf(node.left) + sizeOf(node.right);
        }
    }

    private int sizeOf(Node node) {
        return (node == null) ? 0 : node.size;
    }

    private Node balance(Node node) {
        if (node == null) {
            return null;
        }
        updateSize(node);
        int balance = heightOf(node.left) - heightOf(node.right);
        if (balance > 1) {
            if (heightOf(node.left.left) >= heightOf(node.left.right)) {
                return rotateRight(node);
            } else {
                node.left = rotateLeft(node.left);
                return rotateRight(node);
            }
        } else if (balance < -1) {
            if (heightOf(node.right.right) >= heightOf(node.right.left)) {
                return rotateLeft(node);
            } else {
                node.right = rotateRight(node.right);
                return rotateLeft(node);
            }
        } else {
            return node;
        }
    }

    private Node rotateLeft(Node node) {
        Node newRoot = node.right;
        node.right = newRoot.left;
        newRoot.left = node;
        updateSize(node);
        updateSize(newRoot);
        return newRoot;
    }

    private Node rotateRight(Node node) {
        Node newRoot = node.left;
        node.left = newRoot.right;
        newRoot.right = node;
        updateSize(node);
        updateSize(newRoot);
        return newRoot;
    }

    private int heightOf(Node node) {
        return (node == null) ? -1 : Math.max(heightOf(node.left), heightOf(node.right)) + 1;
    }

    synchronized int count(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        Node node = searchRecursive(root, item);
        return (node == null) ? 0 : node.count;
    }

    synchronized int size() {
        return sizeOf(root);
    }

    synchronized int distinctSize() {
        return countDistinctItems(root, null);
    }

    private int countDistinctItems(Node node, T lastItem) {
        if (node == null) {
            return 0;
        }
        int count = 0;
        if (node.item != lastItem) {
            count++;
        }
        count += countDistinctItems(node.left, node.item);
        count += countDistinctItems(node.right, lastItem);
        return count;
    }

    synchronized T get(int index) {
        if (index < 0 || index >= sizeOf(root)) {
            throw new IndexOutOfBoundsException();
        }
        return getRecursive(root, index);
    }

    private T getRecursive(Node node, int index) {
        if (node == null) {
            throw new NoSuchElementException();
        }
        int leftSize = sizeOf(node.left);
        if (index == leftSize) {
            return node.item;
        } else if (index < leftSize) {
            return getRecursive(node.left, index);
        } else {
            return getRecursive(node.right, index - leftSize - 1);
        }
    }

    synchronized int rank(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        return rankRecursive(root, item);
    }

    private int rankRecursive(Node node, T item) {
        if (node == null) {
            return 0;
        }
        int cmp = order.compare(node.item, item);
        if (cmp < 0) {
            return node.left != null ? sizeOf(node.left) + node.count + rankRecursive(node.right, item) : 0;
        } else if (cmp > 0) {
            return rankRecursive(node.left, item);
        } else {
            int leftSize = sizeOf(node.left);
            return (node.item != null && order.compare(node.item, item) == 0) ? leftSize : rankRecursive(node.left, item);
        }
    }

    synchronized Snapshot<T> snapshot() {
        return new Snapshot<>(this);
    }

    private static class Node {
        T item;
        int count = 1;
        int size = 1;
        Node left, right;

        Node(T item) {
            this.item = item;
        }
    }
}
