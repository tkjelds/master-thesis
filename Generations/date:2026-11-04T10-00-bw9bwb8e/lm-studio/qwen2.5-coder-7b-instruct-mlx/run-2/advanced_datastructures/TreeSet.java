import java.util.NoSuchElementException;

interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeSet<T> treeSet;

    Snapshot(TreeSet<T> treeSet) {
        this.treeSet = new TreeSet<>(treeSet.order);
    }

    public int size() {
        return treeSet.size();
    }

    public T get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return treeSet.getNodeByRank(index).item;
    }

    public boolean contains(T item) {
        return treeSet.contains(item);
    }
}

@TreeSet.ThreadSafe
class TreeSet<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private volatile Node<T> root;
    private int size;

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("Order cannot be null");
        }
        this.order = order;
    }

    private static class Node<T> {
        final T item;
        Node<T> left, right, parent;
        boolean color; // true for red

        Node(T item) {
            this.item = item;
            this.color = true; // New nodes are red
        }
    }

    private boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        if (root == null) {
            root = new Node<>(item);
            size = 1;
            return true;
        }
        Node<T> current = root, parent = null;
        int cmp;
        do {
            cmp = order.compare(current.item, item);
            parent = current;
            if (cmp < 0) {
                current = current.right;
            } else if (cmp > 0) {
                current = current.left;
            } else {
                return false; // Duplicate found
            }
        } while (current != null);
        Node<T> newNode = new Node<>(item);
        if (cmp < 0) {
            parent.right = newNode;
        } else {
            parent.left = newNode;
        }
        newNode.parent = parent;
        fixInsertion(newNode);
        size++;
        return true;
    }

    private void fixInsertion(Node<T> x) {
        while (x != null && x != root && x.parent.color == true) {
            if (parentOf(x) == leftChildOf(parentOf(parentOf(x)))) {
                Node<T> y = rightChildOf(parentOf(parentOf(x)));
                if (y != null && y.color == true) {
                    colorOf(parentOf(x)) = false;
                    colorOf(y) = false;
                    colorOf(parentOf(parentOf(x))) = true;
                    x = parentOf(parentOf(x));
                } else {
                    if (x == rightChildOf(parentOf(x))) {
                        x = parentOf(x);
                        rotateLeft(x);
                    }
                    colorOf(parentOf(x)) = false;
                    colorOf(parentOf(parentOf(x))) = true;
                    rotateRight(parentOf(parentOf(x)));
                }
            } else {
                Node<T> y = leftChildOf(parentOf(parentOf(x)));
                if (y != null && y.color == true) {
                    colorOf(parentOf(x)) = false;
                    colorOf(y) = false;
                    colorOf(parentOf(parentOf(x))) = true;
                    x = parentOf(parentOf(x));
                } else {
                    if (x == leftChildOf(parentOf(x))) {
                        x = parentOf(x);
                        rotateRight(x);
                    }
                    colorOf(parentOf(x)) = false;
                    colorOf(parentOf(parentOf(x))) = true;
                    rotateLeft(parentOf(parentOf(x)));
                }
            }
        }
        colorOf(root) = false;
    }

    private boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        Node<T> z = root;
        while (z != null) {
            int cmp = order.compare(z.item, item);
            if (cmp < 0) {
                z = z.right;
            } else if (cmp > 0) {
                z = z.left;
            } else {
                break;
            }
        }
        if (z == null) {
            return false; // Not found
        }
        size--;
        if (z.left == null) {
            transplant(z, z.right);
            fixDeletion(z.right);
        } else if (z.right == null) {
            transplant(z, z.left);
            fixDeletion(z.left);
        } else {
            Node<T> y = minNode(z.right);
            z.item = y.item;
            transplant(y, y.right);
            fixDeletion(y.right);
        }
        return true;
    }

    private void fixDeletion(Node<T> x) {
        while (x != null && x != root && colorOf(x) == false) {
            if (x == leftChildOf(parentOf(x))) {
                Node<T> w = rightChildOf(parentOf(x));
                if (colorOf(w) == true) {
                    colorOf(w) = false;
                    colorOf(parentOf(x)) = true;
                    rotateLeft(parentOf(x));
                    w = rightChildOf(parentOf(x));
                }
                if (colorOf(leftChildOf(w)) == false && colorOf(rightChildOf(w)) == false) {
                    colorOf(w) = true;
                    x = parentOf(x);
                } else {
                    if (colorOf(rightChildOf(w)) == false) {
                        colorOf(leftChildOf(w)) = false;
                        colorOf(w) = true;
                        rotateRight(w);
                        w = rightChildOf(parentOf(x));
                    }
                    colorOf(w) = colorOf(parentOf(x));
                    colorOf(parentOf(x)) = false;
                    colorOf(rightChildOf(w)) = false;
                    rotateLeft(parentOf(x));
                    x = root;
                }
            } else {
                Node<T> w = leftChildOf(parentOf(x));
                if (colorOf(w) == true) {
                    colorOf(w) = false;
                    colorOf(parentOf(x)) = true;
                    rotateRight(parentOf(x));
                    w = leftChildOf(parentOf(x));
                }
                if (colorOf(rightChildOf(w)) == false && colorOf(leftChildOf(w)) == false) {
                    colorOf(w) = true;
                    x = parentOf(x);
                } else {
                    if (colorOf(leftChildOf(w)) == false) {
                        colorOf(rightChildOf(w)) = false;
                        colorOf(w) = true;
                        rotateLeft(w);
                        w = leftChildOf(parentOf(x));
                    }
                    colorOf(w) = colorOf(parentOf(x));
                    colorOf(parentOf(x)) = false;
                    colorOf(leftChildOf(w)) = false;
                    rotateRight(parentOf(x));
                    x = root;
                }
            }
        }
        colorOf(x) = false;
    }

    private void transplant(Node<T> u, Node<T> v) {
        if (u.parent == null) {
            root = v;
        } else if (u == leftChildOf(u.parent)) {
            u.parent.left = v;
        } else {
            u.parent.right = v;
        }
        if (v != null) {
            v.parent = u.parent;
        }
    }

    private void rotateLeft(Node<T> x) {
        Node<T> y = rightChildOf(x);
        x.right = leftChildOf(y);
        if (leftChildOf(y) != null) {
            leftChildOf(y).parent = x;
        }
        y.parent = parentOf(x);
        if (x.parent == null) {
            root = y;
        } else if (x == leftChildOf(x.parent)) {
            x.parent.left = y;
        } else {
            x.parent.right = y;
        }
        y.left = x;
        x.parent = y;
    }

    private void rotateRight(Node<T> x) {
        Node<T> y = leftChildOf(x);
        x.left = rightChildOf(y);
        if (rightChildOf(y) != null) {
            rightChildOf(y).parent = x;
        }
        y.parent = parentOf(x);
        if (x.parent == null) {
            root = y;
        } else if (x == rightChildOf(x.parent)) {
            x.parent.right = y;
        } else {
            x.parent.left = y;
        }
        y.right = x;
        x.parent = y;
    }

    private Node<T> minNode(Node<T> node) {
        while (leftChildOf(node) != null) {
            node = leftChildOf(node);
        }
        return node;
    }

    private Node<T> getNodeByRank(int rank) {
        Node<T> x = root;
        int leftSize = sizeOfNode(leftChildOf(x));
        while (true) {
            if (rank == leftSize) {
                return x;
            } else if (rank < leftSize) {
                x = leftChildOf(x);
                leftSize = sizeOfNode(leftChildOf(x));
            } else {
                rank -= (leftSize + 1);
                x = rightChildOf(x);
                leftSize = sizeOfNode(leftChildOf(x));
            }
        }
    }

    private int sizeOfNode(Node<T> node) {
        return (node != null) ? node.left.count + node.right.count + 1 : 0;
    }

    private Node<T> parentOf(Node<T> node) {
        return (node != null) ? node.parent : null;
    }

    private Node<T> leftChildOf(Node<T> node) {
        return (node != null) ? node.left : null;
    }

    private Node<T> rightChildOf(Node<T> node) {
        return (node != null) ? node.right : null;
    }

    private boolean colorOf(Node<T> node) {
        return (node != null) ? node.color : false;
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        Node<T> current = root;
        int cmp;
        do {
            if (current == null) {
                return false; // Not found
            }
            cmp = order.compare(current.item, item);
            if (cmp < 0) {
                current = current.right;
            } else if (cmp > 0) {
                current = current.left;
            }
        } while (true);
    }

    public int size() {
        return size;
    }

    public T get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return getNodeByRank(index).item;
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        Node<T> current = root;
        int rank = 0, cmp;
        while (current != null) {
            cmp = order.compare(current.item, item);
            if (cmp < 0) {
                rank += sizeOfNode(leftChildOf(current)) + 1;
                current = current.right;
            } else {
                current = current.left;
            }
        }
        return rank - 1; // Since rank is zero-based
    }

    public Snapshot<T> snapshot() {
        return new Snapshot<>(this);
    }
}
