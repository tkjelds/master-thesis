@BinarySearchTree.ThreadSafe
public final class BinarySearchTree {
    @interface ThreadSafe {}

    private static final class Node {
        int value;
        Node left;
        Node right;

        Node(int value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node root;
    private int size;

    /**
     * Inserts value if it is not already present.
     *
     * @return true if the value was inserted, false if it was already present
     */
    public boolean insert(int value) {
        synchronized (lock) {
            if (root == null) {
                root = new Node(value);
                size++;
                return true;
            }

            Node current = root;
            while (true) {
                if (value == current.value) {
                    return false;
                } else if (value < current.value) {
                    if (current.left == null) {
                        current.left = new Node(value);
                        size++;
                        return true;
                    }
                    current = current.left;
                } else {
                    if (current.right == null) {
                        current.right = new Node(value);
                        size++;
                        return true;
                    }
                    current = current.right;
                }
            }
        }
    }

    public boolean contains(int value) {
        synchronized (lock) {
            Node current = root;
            while (current != null) {
                if (value == current.value) {
                    return true;
                }
                current = value < current.value ? current.left : current.right;
            }
            return false;
        }
    }

    /**
     * Removes value if present.
     *
     * @return true if the value was removed, false if it was not present
     */
    public boolean remove(int value) {
        synchronized (lock) {
            Node parent = null;
            Node current = root;

            while (current != null && current.value != value) {
                parent = current;
                current = value < current.value ? current.left : current.right;
            }

            if (current == null) {
                return false;
            }

            if (current.left != null && current.right != null) {
                Node successorParent = current;
                Node successor = current.right;
                while (successor.left != null) {
                    successorParent = successor;
                    successor = successor.left;
                }

                current.value = successor.value;
                if (successorParent == current) {
                    successorParent.right = successor.right;
                } else {
                    successorParent.left = successor.right;
                }
            } else {
                Node replacement = current.left != null ? current.left : current.right;
                if (parent == null) {
                    root = replacement;
                } else if (parent.left == current) {
                    parent.left = replacement;
                } else {
                    parent.right = replacement;
                }
            }

            size--;
            return true;
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

    public void clear() {
        synchronized (lock) {
            root = null;
            size = 0;
        }
    }

    /**
     * Returns the smallest value.
     *
     * @throws IllegalStateException if the tree is empty
     */
    public int min() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }

            Node current = root;
            while (current.left != null) {
                current = current.left;
            }
            return current.value;
        }
    }

    /**
     * Returns the largest value.
     *
     * @throws IllegalStateException if the tree is empty
     */
    public int max() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }

            Node current = root;
            while (current.right != null) {
                current = current.right;
            }
            return current.value;
        }
    }

    /**
     * Returns a sorted snapshot of the values in the tree.
     */
    public int[] toArray() {
        synchronized (lock) {
            int[] values = new int[size];
            Node[] stack = new Node[size];
            int stackSize = 0;
            int index = 0;
            Node current = root;

            while (current != null || stackSize > 0) {
                while (current != null) {
                    stack[stackSize++] = current;
                    current = current.left;
                }

                current = stack[--stackSize];
                values[index++] = current.value;
                current = current.right;
            }

            return values;
        }
    }
}
