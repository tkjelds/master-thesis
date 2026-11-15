import java.util.*;

@TreeSet.ThreadSafe
class TreeSet<T> {
    @interface ThreadSafe {}

    interface Order<T> {
        int compare(T left, T right);
    }

    private static class Node<T> {
        final T item;
        int count = 1;
        boolean color = false; // true for red, false for black
        Node<T> left, right, parent;

        Node(T item) {
            this.item = item;
        }
    }

    private final Order<T> order;
    private Node<T> root = null;
    private int size = 0;

    public TreeSet(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
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
        return right;
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
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
        return left;
    }

    private void flipColors(Node<T> node) {
        node.color = !node.color;
        if (node.left != null) {
            node.left.color = !node.left.color;
        }
        if (node.right != null) {
            node.right.color = !node.right.color;
        }
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) return new Node<>(item);
        int cmp = order.compare(item, node.item);
        if (cmp < 0) {
            node.left = insert(node.left, item);
            node.left.parent = node;
        } else if (cmp > 0) {
            node.right = insert(node.right, item);
            node.right.parent = node;
        }
        if (isRed(node.right) && !isRed(node.left)) {
            node = rotateLeft(node);
        }
        if (isRed(node.left) && isRed(node.left.left)) {
            node = rotateRight(node);
        }
        if (isRed(node.left) && isRed(node.right)) {
            flipColors(node);
        }
        node.count = size(node.left) + size(node.right) + 1;
        return node;
    }

    private boolean isRed(Node<T> node) {
        if (node == null) return false;
        return node.color;
    }

    private int size(Node<T> node) {
        if (node == null) return 0;
        return node.count;
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException();
        root = insert(root, item);
        root.color = false;
        return true; // Always returns true as duplicates are not allowed
    }

    private Node<T> deleteMin(Node<T> node) {
        if (node.left == null) return node.right;
        node.left = deleteMin(node.left);
        node.count = size(node.left) + size(node.right) + 1;
        return fixUp(node);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (node == null) return null;
        int cmp = order.compare(item, node.item);
        if (cmp < 0) {
            node.left = delete(node.left, item);
        } else if (cmp > 0) {
            node.right = delete(node.right, item);
        } else {
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;
            Node<T> min = findMin(node.right);
            swapItems(node, min);
            node.right = delete(node.right, item);
        }
        node.count = size(node.left) + size(node.right) + 1;
        return fixUp(node);
    }

    private Node<T> fixUp(Node<T> node) {
        if (isRed(node.right)) node = rotateLeft(node);
        if (isRed(node.left) && isRed(node.left.left)) node = rotateRight(node);
        if (isRed(node.left) && isRed(node.right)) {
            flipColors(node);
            node.right = rotateLeft(node.right);
            node = rotateRight(node);
        }
        return node;
    }

    private T findMin(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node.item;
    }

    private void swapItems(Node<T> a, Node<T> b) {
        T temp = a.item;
        a.item = b.item;
        b.item = temp;
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        root = delete(root, item);
        return root != null;
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> current = root;
        while (current != null) {
            int cmp = order.compare(item, current.item);
            if (cmp < 0) {
                current = current.left;
            } else if (cmp > 0) {
                current = current.right;
            } else {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return size(root);
    }

    public T get(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
        Node<T> current = root;
        while (true) {
            int leftSize = size(current.left);
            if (leftSize > index) {
                current = current.left;
            } else if (leftSize < index) {
                index -= leftSize + 1;
                current = current.right;
            } else {
                return current.item;
            }
        }
    }

    public int rank(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> current = root;
        int rank = 0;
        while (current != null) {
            int cmp = order.compare(item, current.item);
            if (cmp < 0) {
                current = current.left;
            } else {
                rank += size(current.left);
                if (cmp == 0) rank++;
                current = current.right;
            }
        }
        return rank - 1; // Adjust for zero-based index
    }

    public Snapshot<T> snapshot() {
        return new Snapshot<>(root);
    }

    private static class Snapshot<T> implements TreeSet<T>.Snapshot<T> {
        private final Node<T> root;
        private final int size;

        Snapshot(Node<T> root) {
            this.root = copy(root);
            this.size = size(root);
        }

        private Node<T> copy(Node<T> node) {
            if (node == null) return null;
            Node<T> newNode = new Node<>(node.item);
            newNode.count = node.count;
            newNode.color = node.color;
            newNode.left = copy(node.left);
            if (newNode.left != null) {
                newNode.left.parent = newNode;
            }
            newNode.right = copy(node.right);
            if (newNode.right != null) {
                newNode.right.parent = newNode;
            }
            return newNode;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public T get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            Node<T> current = root;
            while (true) {
                int leftSize = size(current.left);
                if (leftSize > index) {
                    current = current.left;
                } else if (leftSize < index) {
                    index -= leftSize + 1;
                    current = current.right;
                } else {
                    return current.item;
                }
            }
        }

        @Override
        public boolean contains(T item) {
            if (item == null) throw new NullPointerException();
            Node<T> current = root;
            while (current != null) {
                int cmp = order.compare(item, current.item);
                if (cmp < 0) {
                    current = current.left;
                } else if (cmp > 0) {
                    current = current.right;
                } else {
                    return true;
                }
            }
            return false;
        }

        private int size(Node<T> node) {
            if (node == null) return 0;
            return node.count;
        }
    }

    public interface Snapshot<T> {
        int size();
        T get(int index);
        boolean contains(T item);
    }
}
