```java
interface Order<T> {
    int compare(T left, T right);
}

class RedBlackTree<T> {
    private static final boolean RED = true;
    private static final boolean BLACK = false;
    private Node<T> root;
    private Order<T> order;

    public RedBlackTree(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    private static class Node<T> {
        T item;
        Node<T> left, right, parent;
        boolean color;

        Node(T item, Node<T> parent, boolean color) {
            this.item = item;
            this.parent = parent;
            this.color = color;
        }
    }

    private Node<T> newNode(T item) {
        if (item == null) throw new NullPointerException();
        return new Node<>(item, null, RED);
    }

    private boolean isRed(Node<T> node) {
        return (node != null && node.color == RED);
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
        node.color = RED;
        right.color = BLACK;
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
        node.color = RED;
        left.color = BLACK;
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

    private Node<T> balance(Node<T> node) {
        if (isRed(node.right)) {
            node = rotateLeft(node);
        }
        if (isRed(node.left) && isRed(node.left.left)) {
            node = rotateRight(node);
        }
        if (isRed(node.left) && isRed(node.right)) {
            flipColors(node);
        }
        return node;
    }

    public boolean add(T item) {
        Node<T> newNode = newNode(item);
        if (root == null) {
            root = newNode;
            root.color = BLACK;
            return true;
        }
        Node<T> current = root;
        while (true) {
            int cmp = order.compare(item, current.item);
            if (cmp == 0) return false;
            Node<T> next = cmp < 0 ? current.left : current.right;
            if (next == null) {
                next = newNode;
                if (cmp < 0) current.left = next;
                else current.right = next;
                next.parent = current;
                next.color = RED;
                fixUp(next);
                return true;
            }
            current = next;
        }
    }

    private void fixUp(Node<T> node) {
        while (isRed(node.parent)) {
            if (node.parent == node.parent.parent.left) {
                Node<T> uncle = node.parent.parent.right;
                if (isRed(uncle)) {
                    flipColors(node.parent.parent);
                    node = node.parent;
                } else {
                    if (node == node.parent.right) {
                        node = node.parent;
                        rotateLeft(node);
                    }
                    node.parent.color = BLACK;
                    node.parent.parent.color = RED;
                    rotateRight(node.parent.parent);
                }
            } else {
                Node<T> uncle = node.parent.parent.left;
                if (isRed(uncle)) {
                    flipColors(node.parent.parent);
                    node = node.parent;
                } else {
                    if (node == node.parent.left) {
                        node = node.parent;
                        rotateRight(node);
                    }
                    node.parent.color = BLACK;
                    node.parent.parent.color = RED;
                    rotateLeft(node.parent.parent);
                }
            }
        }
        root.color = BLACK;
    }

    public boolean remove(T item) {
        Node<T> node = find(item);
        if (node == null) return false;
        delete(node);
        return true;
    }

    private Node<T> find(T item) {
        Node<T> current = root;
        while (current != null) {
            int cmp = order.compare(item, current.item);
            if (cmp == 0) return current;
            current = cmp < 0 ? current.left : current.right;
        }
        return null;
    }

    private void delete(Node<T> node) {
        Node<T> min = getMin(node.right);
        if (min != null) {
            node.item = min.item;
            Node<T> temp = min.right;
            int cmp = order.compare(node, min);
            if (cmp == 0) {
                node.right = temp;
            } else {
                Node<T> parent = min.parent;
                if (min == parent.left) parent.left = temp;
                else parent.right = temp;
            }
            min.parent = null;
        } else {
            Node<T> parent = node.parent;
            int cmp = order.compare(node, node.left);
            if (cmp == 0) {
                node.left = null;
            } else {
                Node<T> temp = node.right;
                if (node == parent.left) parent.left = temp;
                else parent.right = temp;
            }
            node.parent = null;
        }
        fixDeletion(node);
    }

    private Node<T> getMin(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private void fixDeletion(Node<T> node) {
        while (node != root && !isRed(node)) {
            if (node == node.parent.left) {
                Node<T> sibling = node.parent.right;
                if (isRed(sibling)) {
                    flipColors(node.parent);
                    sibling = node.parent.right;
                }
                if (!isRed(sibling.left) && !isRed(sibling.right)) {
                    sibling.color = RED;
                    node = node.parent;
                } else {
                    if (!isRed(sibling.right)) {
                        flipColors(sibling);
                        sibling = node.parent.right;
                        rotateRight(sibling);
                    }
                    sibling.color = node.parent.color;
                    node.parent.color = BLACK;
                    sibling.right.color = BLACK;
                    rotateLeft(node.parent);
                    node = root;
                }
            } else {
                Node<T> sibling = node.parent.left;
                if (isRed(sibling)) {
                    flipColors(node.parent);
                    sibling = node.parent.left;
                }
                if (!isRed(sibling.left) && !isRed(sibling.right)) {
                    sibling.color = RED;
                    node = node.parent;
                } else {
                    if (!isRed(sibling.left)) {
                        flipColors(sibling);
                        sibling = node.parent.left;
                        rotateLeft(sibling);
                    }
                    sibling.color = node.parent.color;
                    node.parent.color = BLACK;
                    sibling.left.color = BLACK;
                    rotateRight(node.parent);
                    node = root;
                }
            }
        }
        node.color = BLACK;
    }

    public boolean contains(T item) {
        return find(item) != null;
    }

    public T first() {
        if (isEmpty()) throw new IllegalStateException();
        return getMin(root).item;
    }

    public T last() {
        if (isEmpty()) throw new IllegalStateException();
        return getMax(root).item;
    }

    private Node<T> getMax(Node<T> node) {
        while (node.right != null) {
            node = node.right;
        }
        return node;
    }

    public int size() {
        return size(root);
    }

    private int size(Node<T> node) {
        if (node == null) return 0;
        return 1 + size(node.left) + size(node.right);
    }

    public Object[] snapshot() {
        return snapshotToArray(root, new ArrayList<>());
    }

    private Object[] snapshotToArray(Node<T> node, List<T> list) {
        if (node == null) return list.toArray();
        snapshotToArray(node.left, list);
        list.add(node.item);
        snapshotToArray(node.right, list);
        return list.toArray();
    }

    private boolean isEmpty() {
        return root == null;
    }
}
```
