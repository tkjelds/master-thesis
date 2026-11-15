import java.util.NoSuchElementException;

interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeSet<T> treeSet;

    public Snapshot(TreeSet<T> treeSet) {
        this.treeSet = new TreeSet<>(treeSet.order);
        for (T item : treeSet.root) {
            this.treeSet.add(item);
        }
    }

    public int size() {
        return treeSet.size();
    }

    public T get(int index) {
        if (index < 0 || index >= treeSet.size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + treeSet.size());
        }
        return treeSet.root.get(index);
    }

    public boolean contains(T item) {
        return treeSet.contains(item);
    }
}

@TreeSet.ThreadSafe
class TreeSet<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private Node root = null;
    private int size;

    public TreeSet(Order<T> order) {
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        synchronized (this) {
            Node node = find(item);
            if (node != null && order.compare(node.item, item) == 0) {
                return false;
            }
            node = new Node(item);
            insert(node);
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        synchronized (this) {
            Node node = find(item);
            if (node != null && order.compare(node.item, item) == 0) {
                delete(node);
                size--;
                return true;
            }
            return false;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        synchronized (this) {
            Node node = find(item);
            return node != null && order.compare(node.item, item) == 0;
        }
    }

    public int size() {
        return size;
    }

    public T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        synchronized (this) {
            return root.get(index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        synchronized (this) {
            int count = 0;
            Node current = root;
            while (current != null) {
                int cmp = order.compare(current.item, item);
                if (cmp < 0) {
                    count += 1 + current.leftCount;
                    current = current.right;
                } else if (cmp > 0) {
                    current = current.left;
                } else {
                    return count;
                }
            }
            throw new NoSuchElementException("Item not found in the set");
        }
    }

    public Snapshot<T> snapshot() {
        return new Snapshot<>(this);
    }

    private Node find(T item) {
        Node current = root;
        while (current != null) {
            int cmp = order.compare(current.item, item);
            if (cmp < 0) {
                current = current.right;
            } else if (cmp > 0) {
                current = current.left;
            } else {
                return current;
            }
        }
        return null;
    }

    private void insert(Node node) {
        if (root == null) {
            root = node;
            return;
        }
        Node parent = null;
        Node current = root;
        while (current != null) {
            parent = current;
            int cmp = order.compare(current.item, node.item);
            if (cmp < 0) {
                current.leftCount++;
                current = current.right;
            } else if (cmp > 0) {
                current.leftCount++;
                current = current.left;
            } else {
                return; // Node already exists
            }
        }
        if (order.compare(parent.item, node.item) < 0) {
            parent.right = node;
        } else {
            parent.left = node;
        }
        node.parent = parent;
        balance(node);
    }

    private void delete(Node node) {
        Node child = (node.left == null) ? node.right : node.left;
        if (child != null) {
            child.parent = node.parent;
            if (node.parent == null) {
                root = child;
            } else if (node == node.parent.left) {
                node.parent.left = child;
            } else {
                node.parent.right = child;
            }
            node.leftCount--;
        } else {
            if (node.parent == null) {
                root = null;
            } else if (node == node.parent.left) {
                node.parent.left = null;
            } else {
                node.parent.right = null;
            }
        }
    }

    private void balance(Node node) {
        while (node != null) {
            Node parent = node.parent;
            if (parent == null) {
                root = node;
                break;
            }
            Node sibling = parent.left == node ? parent.right : parent.left;
            if (sibling != null) {
                boolean siblingRed = node.color == Color.RED;
                boolean parentBlack = parent.color == Color.BLACK;
                if (siblingColor(sibling) == Color.RED) {
                    sibling.color = Color.BLACK;
                    parent.color = Color.RED;
                    if (parent == root) {
                        break;
                    }
                    Node uncle = parent.parent.left == parent ? parent.parent.right : parent.parent.left;
                    if (uncle != null && uncle.color == Color.RED) {
                        uncle.color = Color.BLACK;
                    } else {
                        rotate(parent);
                        break;
                    }
                } else if (!siblingRed && !isRed(sibling.left) && !isRed(sibling.right)) {
                    sibling.color = Color.RED;
                    if (parentBlack) {
                        node = parent;
                        continue;
                    }
                } else {
                    if (sibling == parent.right && isRed(sibling.left) && !isRed(sibling.right)) {
                        sibling = rotateLeft(sibling);
                        parent.right = sibling;
                        sibling.left.color = Color.BLACK;
                    } else if (sibling == parent.left && isRed(sibling.right) && !isRed(sibling.left)) {
                        sibling = rotateRight(sibling);
                        parent.left = sibling;
                        sibling.right.color = Color.BLACK;
                    }
                    sibling.color = parent.color;
                    parent.color = Color.BLACK;
                    if (sibling == parent.right) {
                        rotateLeft(parent);
                    } else {
                        rotateRight(parent);
                    }
                    break;
                }
            } else if (parentBlack) {
                node = parent;
                continue;
            }
            break;
        }
    }

    private Node rotateLeft(Node node) {
        Node right = node.right;
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
        right.leftCount = 1 + node.leftCount + (node.right != null ? node.right.leftCount : 0);
        return right;
    }

    private Node rotateRight(Node node) {
        Node left = node.left;
        node.left = left.right;
        if (left.right != null) {
            left.right.parent = node;
        }
        left.parent = node.parent;
        if (node.parent == null) {
            root = left;
        } else if (node == node.parent.left) {
            node.parent.left = left;
        } else {
            node.parent.right = left;
        }
        left.rightCount = 1 + node.rightCount + (node.left != null ? node.left.rightCount : 0);
        return left;
    }

    private Color siblingColor(Node node) {
        Node parent = node.parent;
        if (parent == null) {
            return Color.BLACK;
        }
        Node sibling = parent.left == node ? parent.right : parent.left;
        return sibling != null ? sibling.color : Color.BLACK;
    }

    private boolean isRed(Node node) {
        return node != null && node.color == Color.RED;
    }

    private enum Color {
        RED, BLACK
    }

    static class Node {
        T item;
        Color color = Color.RED;
        int leftCount = 0;
        Node parent, left, right;

        Node(T item) {
            this.item = item;
        }

        T get(int index) {
            int leftSize = (left != null) ? left.leftCount : 0;
            if (index < leftSize) {
                return left.get(index);
            } else if (index == leftSize) {
                return item;
            } else {
                return right.get(index - 1 - leftSize);
            }
        }
    }
}
