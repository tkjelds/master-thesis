public interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T item;
        int count;
        volatile Node<T> left, right;
        boolean isRed;

        Node(T item) {
            this.item = item;
            count = 1;
            left = right = null;
            isRed = true; // New nodes are red
        }
    }

    private final Order<T> order;
    private Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    private synchronized boolean add(T item, Node<T> parent) {
        if (item == null) throw new NullPointerException();
        int cmp;
        Node<T> child = parent.left;
        if (child == null) {
            parent.left = new Node<>(item);
            updateCountsAndFix(parent);
            return true;
        } else {
            cmp = order.compare(item, child.item);
            if (cmp < 0) return add(item, child);
            else if (cmp == 0) return false; // Duplicate item
            else return add(item, child.right);
        }
    }

    public boolean add(T item) {
        synchronized (this) {
            if (root == null) {
                root = new Node<>(item);
                return true;
            } else return add(item, root);
        }
    }

    private synchronized boolean remove(T item, Node<T> parent) {
        if (item == null) throw new NullPointerException();
        Node<T> child = parent.left;
        int cmp;
        if (child == null) return false; // Not found
        else {
            cmp = order.compare(item, child.item);
            if (cmp < 0) return remove(item, child);
            else if (cmp == 0) {
                if (child.right != null) {
                    Node<T> successor = child;
                    for (successor = successor.right; successor.left != null; successor = successor.left);
                    child.item = successor.item;
                    return remove(successor.item, child);
                } else {
                    if (child.isRed) {
                        parent.left = child.right;
                        updateCounts(parent);
                    } else {
                        Node<T> temp = new Node<>(null); // Pseudo-parent
                        temp.isRed = true;
                        temp.left = child;
                        balanceDeletion(temp, child);
                        root = temp.left;
                    }
                    return true;
                }
            } else return remove(item, child.right);
        }
    }

    private void balanceDeletion(Node<T> parent, Node<T> child) {
        while (child != root && !child.isRed) {
            if (child == parent.left) {
                Node<T> sibling = parent.right;
                if (sibling.isRed) {
                    sibling.isRed = false;
                    parent.isRed = true;
                    rotateLeft(parent);
                    sibling = parent.right;
                }
                if (!sibling.left.isRed && !sibling.right.isRed) {
                    sibling.isRed = true;
                    child = parent;
                } else {
                    if (!sibling.right.isRed) {
                        sibling.left.isRed = false;
                        sibling.isRed = true;
                        rotateRight(sibling);
                        sibling = parent.right;
                    }
                    sibling.isRed = parent.isRed;
                    parent.isRed = false;
                    sibling.right.isRed = false;
                    rotateLeft(parent);
                    child = root;
                }
            } else {
                Node<T> sibling = parent.left;
                if (sibling.isRed) {
                    sibling.isRed = false;
                    parent.isRed = true;
                    rotateRight(parent);
                    sibling = parent.left;
                }
                if (!sibling.right.isRed && !sibling.left.isRed) {
                    sibling.isRed = true;
                    child = parent;
                } else {
                    if (!sibling.left.isRed) {
                        sibling.right.isRed = false;
                        sibling.isRed = true;
                        rotateLeft(sibling);
                        sibling = parent.left;
                    }
                    sibling.isRed = parent.isRed;
                    parent.isRed = false;
                    sibling.left.isRed = false;
                    rotateRight(parent);
                    child = root;
                }
            }
        }
        child.isRed = false;
    }

    private synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        return remove(item, root);
    }

    private synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> current = root;
        while (current != null) {
            int cmp = order.compare(item, current.item);
            if (cmp < 0) current = current.left;
            else if (cmp == 0) return true;
            else current = current.right;
        }
        return false;
    }

    private synchronized int size() {
        return root == null ? 0 : root.count;
    }

    private synchronized T get(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
        Node<T> current = root;
        while (true) {
            int leftSize = current.left != null ? current.left.count : 0;
            if (index < leftSize) current = current.left;
            else if (index == leftSize) return current.item;
            else {
                index -= leftSize + 1;
                current = current.right;
            }
        }
    }

    private synchronized int rank(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> current = root;
        int rank = 0;
        while (current != null) {
            int cmp = order.compare(item, current.item);
            if (cmp < 0) current = current.left;
            else {
                rank += current.count - (current.right != null ? current.right.count : 0) - (cmp == 0 && !current.isRed ? 1 : 0);
                if (cmp == 0) break;
                else current = current.right;
            }
        }
        return rank;
    }

    private synchronized void updateCountsAndFix(Node<T> node) {
        while (true) {
            if (node == null || node.isRed && !node.parent().isRed) break;
            Node<T> parent = node.parent();
            if (parent.left == node) {
                Node<T> sibling = parent.right;
                assert(!sibling.isRed); // Red, black
                if (sibling.left != null && sibling.left.isRed) {
                    rotateLeft(sibling);
                }
                sibling = parent.right;
                balanceInsertion(parent, sibling);
            } else {
                Node<T> sibling = parent.left;
                assert(!sibling.isRed); // Red, black
                if (sibling.right != null && sibling.right.isRed) {
                    rotateRight(sibling);
                }
                sibling = parent.left;
                balanceInsertion(parent, sibling);
            }
        }
    }

    private synchronized void updateCounts(Node<T> node) {
        while (true) {
            if (node == null || node.isRed && !node.parent().isRed) break;
            Node<T> parent = node.parent();
            if (parent.left == node) {
                Node<T> sibling = parent.right;
                assert(!sibling.isRed); // Red, black
                if (sibling.left != null && sibling.left.isRed) {
                    rotateLeft(sibling);
                }
                sibling = parent.right;
                balanceInsertion(parent, sibling);
            } else {
                Node<T> sibling = parent.left;
                assert(!sibling.isRed); // Red, black
                if (sibling.right != null && sibling.right.isRed) {
                    rotateRight(sibling);
                }
                sibling = parent.left;
                balanceInsertion(parent, sibling);
            }
        }
    }

    private static <T> Node<T> parentOf(Node<T> node) {
        return node != null ? node.parent() : null;
    }

    private static <T> Node<T> rotateLeft(Node<T> node) {
        Node<T> newRoot = node.right;
        node.right = newRoot.left;
        if (newRoot.left != null) newRoot.left.parent = node;
        newRoot.parent = parentOf(node);
        if (parentOf(node) == null) return newRoot;
        else {
            if (parentOf(node).left == node) parentOf(node).left = newRoot;
            else parentOf(node).right = newRoot;
        }
        newRoot.left = node;
        node.parent = newRoot;
        updateCounts(node);
        updateCounts(newRoot);
        return newRoot;
    }

    private static <T> Node<T> rotateRight(Node<T> node) {
        Node<T> newRoot = node.left;
        node.left = newRoot.right;
        if (newRoot.right != null) newRoot.right.parent = node;
        newRoot.parent = parentOf(node);
        if (parentOf(node) == null) return newRoot;
        else {
            if (parentOf(node).left == node) parentOf(node).left = newRoot;
            else parentOf(node).right = newRoot;
        }
        newRoot.right = node;
        node.parent = newRoot;
        updateCounts(node);
        updateCounts(newRoot);
        return newRoot;
    }

    private void balanceInsertion(Node<T> parent, Node<T> sibling) {
        if (sibling.isRed) {
            parent.isRed = false;
            sibling.isRed = false;
            if (parent.parent() != null) parent.parent().isRed = true;
        } else {
            balanceInsertionCase1(parent);
        }
    }

    private void balanceInsertionCase1(Node<T> parent) {
        if (parent.parent() == null) return;
        else balanceInsertionCase2(parent);
    }

    private void balanceInsertionCase2(Node<T> parent) {
        Node<T> uncle = siblingOf(parent);
        if (uncle != null && uncle.isRed) {
            parent.isRed = false;
            uncle.isRed = false;
            if (parent.parent() != null) parent.parent().isRed = true;
        } else {
            balanceInsertionCase3(parent);
        }
    }

    private void balanceInsertionCase3(Node<T> parent) {
        if (parent.parent() != null && !parent.isRed && !leftOf(parent).isRed) {
            parent.left.isRed = false;
            rotateRight(parent);
        } else {
            balanceInsertionCase4(parent);
        }
    }

    private void balanceInsertionCase4(Node<T> parent) {
        if (parent.parent() != null && !parent.isRed && rightOf(parent).isRed) {
            rotateLeft(parent);
        } else {
            balanceInsertionCase5(parent);
        }
    }

    private void balanceInsertionCase5(Node<T> parent) {
        if (parent.parent() != null) {
            parent.isRed = false;
            parent.parent().isRed = true;
        }
    }

    private Node<T> siblingOf(Node<T> node) {
        return parentOf(node).right == node ? parentOf(node).left : parentOf(node).right;
    }

    private Node<T> leftOf(Node<T> node) {
        return parentOf(node).left == node ? parentOf(node).right : parentOf(node).left;
    }

    private Node<T> rightOf(Node<T> node) {
        return parentOf(node).left == node ? parentOf(node).right : parentOf(node).left;
    }

    private static final class Snapshot<T> {
        private final Node<T> rootSnapshot;

        Snapshot(Node<T> root) {
            this.rootSnapshot = copyTree(root);
        }

        private Node<T> copyTree(Node<T> node) {
            if (node == null) return null;
            Node<T> newNode = new Node<>(node.item);
            newNode.count = node.count;
            newNode.left = copyTree(node.left);
            newNode.right = copyTree(node.right);
            return newNode;
        }

        public int size() {
            return rootSnapshot.count;
        }

        public T get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
            Node<T> current = rootSnapshot;
            while (true) {
                int leftSize = current.left != null ? current.left.count : 0;
                if (index < leftSize) current = current.left;
                else if (index == leftSize) return current.item;
                else {
                    index -= leftSize + 1;
                    current = current.right;
                }
            }
        }

        public boolean contains(T item) {
            if (item == null) throw new NullPointerException();
            Node<T> current = rootSnapshot;
            while (current != null) {
                int cmp = order.compare(item, current.item);
                if (cmp < 0) current = current.left;
                else if (cmp == 0) return true;
                else current = current.right;
            }
            return false;
        }

        boolean isConsistent() {
            return checkInvariants(rootSnapshot);
        }

        private boolean checkInvariants(Node<T> node) {
            if (node == null) return true;
            Node<T> left = node.left, right = node.right;
            boolean isExternal = (left == null) && (right == null);
            if (!isExternal) {
                boolean leftInvariants = checkInvariants(left) && (left != null ? order.compare(node.item, left.item) > 0 : true);
                boolean rightInvariants = checkInvariants(right) && (right != null ? order.compare(node.item, right.item) < 0 : true);
                if (!leftInvariants || !rightInvariants) return false;
                boolean blackHeightConsistent = (isExternal ? true : (left.isRed() == right.isRed()));
                if (!blackHeightConsistent) return false;
            }
            int leftBlackHeight = (isExternal ? 1 : (left != null ? left.blackHeight() : 0));
            int rightBlackHeight = (isExternal ? 1 : (right != null ? right.blackHeight() : 0));
            boolean blackHeightConsistentWithChildren = (isExternal ? true : (leftBlackHeight == rightBlackHeight));
            if (!blackHeightConsistentWithChildren) return false;
            node.count = (left != null ? left.count : 0) + (right != null ? right.count : 0) + 1;
            return true;
        }

        private boolean isRed() {
            return false; // Dummy implementation
        }

        int blackHeight() {
            return 1; // Dummy implementation
        }
    }
}
