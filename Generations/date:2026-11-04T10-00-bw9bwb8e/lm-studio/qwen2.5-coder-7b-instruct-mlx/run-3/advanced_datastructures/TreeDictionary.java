import java.util.concurrent.atomic.AtomicInteger;

interface Order<K> {
    int compare(K left, K right);
}

@TreeDictionary.ThreadSafe
class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private Node root;
    private Order<K> order;

    public TreeDictionary(Order<K> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
        this.root = null;
    }

    private class Node {
        K key;
        V value;
        Node left, right, parent;
        boolean red;

        public Node(K key, V value) {
            this.key = key;
            this.value = value;
            left = right = parent = null;
            red = true; // Newly created nodes are always red
        }

        public void updateColor() {
            if (parent != root && parent.red) {
                if (parent.parent.left == parent) {
                    Node uncle = parent.parent.right;
                    if (uncle != null && uncle.red) {
                        // Case 1: Parent and uncle are both red
                        parent.red = false;
                        uncle.red = false;
                        parent.parent.red = true;
                    } else {
                        // Case 2: Uncle is black
                        if (this == parent.right) {
                            // Case 2.1: Node is a right child
                            rotateLeft(parent);
                        }
                        // Case 2.2: Node is a left child
                        rotateRight(parent.parent);
                        parent.red = false;
                    }
                } else {
                    // The mirror case of the above
                    Node uncle = parent.parent.left;
                    if (uncle != null && uncle.red) {
                        // Case 1: Parent and uncle are both red
                        parent.red = false;
                        uncle.red = false;
                        parent.parent.red = true;
                    } else {
                        // Case 2: Uncle is black
                        if (this == parent.left) {
                            // Case 2.1: Node is a left child
                            rotateRight(parent);
                        }
                        // Case 2.2: Node is a right child
                        rotateLeft(parent.parent);
                        parent.red = false;
                    }
                }
            } else if (parent == root) {
                parent.red = false; // Ensure root is black
            }
        }

        public Node find(K key) {
            int cmp = order.compare(key, this.key);
            if (cmp == 0) return this;
            else if (cmp < 0 && left != null) return left.find(key);
            else if (right != null) return right.find(key);
            else return null;
        }

        public Node findMin() {
            if (left != null) return left.findMin();
            else return this;
        }

        public void remove() {
            Node x = this, y = null, z = null;
            boolean wasRed;

            if (x.left == null) {
                y = x.right;
                transplant(x, x.right);
            } else if (x.right == null) {
                y = x.left;
                transplant(x, x.left);
            } else {
                z = x.right.findMin();
                wasRed = z.red;
                y = z.right;

                if (z.parent == x) {
                    y.parent = z;
                } else {
                    transplant(z, z.right);
                    z.right = x.right;
                    z.right.parent = z;
                }

                transplant(x, z);
                z.left = x.left;
                z.left.parent = z;
                z.red = x.red;
            }

            if (!wasRed && y != null) {
                while (y != root && !y.red) {
                    if (y == y.parent.left) {
                        Node sibling = y.parent.right;
                        if (sibling.red) {
                            sibling.red = false;
                            y.parent.red = true;
                            rotateLeft(y.parent);
                            sibling = y.parent.right;
                        }

                        if ((sibling.left == null || !sibling.left.red) &&
                            (sibling.right == null || !sibling.right.red)) {
                            sibling.red = true;
                            y = y.parent;
                        } else {
                            if (sibling.right == null || !sibling.right.red) {
                                sibling.left.red = false;
                                sibling.red = true;
                                rotateRight(sibling);
                                sibling = y.parent.right;
                            }

                            sibling.red = y.parent.red;
                            y.parent.red = false;
                            sibling.right.red = false;
                            rotateLeft(y.parent);
                            y = root;
                        }
                    } else {
                        Node sibling = y.parent.left;

                        if (sibling.red) {
                            sibling.red = false;
                            y.parent.red = true;
                            rotateRight(y.parent);
                            sibling = y.parent.left;
                        }

                        if ((sibling.right == null || !sibling.right.red) &&
                            (sibling.left == null || !sibling.left.red)) {
                            sibling.red = true;
                            y = y.parent;
                        } else {
                            if (sibling.left == null || !sibling.left.red) {
                                sibling.right.red = false;
                                sibling.red = true;
                                rotateLeft(sibling);
                                sibling = y.parent.left;
                            }

                            sibling.red = y.parent.red;
                            y.parent.red = false;
                            sibling.left.red = false;
                            rotateRight(y.parent);
                            y = root;
                        }
                    }
                }

                if (y != null) {
                    y.red = false;
                }
            }
        }

        public void rotateLeft(Node x) {
            Node y = x.right;
            x.right = y.left;

            if (y.left != null) {
                y.left.parent = x;
            }

            y.parent = x.parent;

            if (x.parent == null) {
                root = y;
            } else if (x == x.parent.left) {
                x.parent.left = y;
            } else {
                x.parent.right = y;
            }

            y.left = x;
            x.parent = y;
        }

        public void rotateRight(Node y) {
            Node x = y.left;
            y.left = x.right;

            if (x.right != null) {
                x.right.parent = y;
            }

            x.parent = y.parent;

            if (y.parent == null) {
                root = x;
            } else if (y == y.parent.right) {
                y.parent.right = x;
            } else {
                y.parent.left = x;
            }

            x.right = y;
            y.parent = x;
        }
    }

    private void transplant(Node u, Node v) {
        if (u.parent == null) {
            root = v;
        } else if (u == u.parent.left) {
            u.parent.left = v;
        } else {
            u.parent.right = v;
        }

        if (v != null) {
            v.parent = u.parent;
        }
    }

    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException();
        Node z = new Node(key, value);
        Node y = null;
        Node x = root;

        while (x != null) {
            y = x;
            int cmp = order.compare(z.key, x.key);
            if (cmp < 0) {
                x = x.left;
            } else if (cmp > 0) {
                x = x.right;
            } else {
                V oldValue = x.value;
                x.value = z.value;
                return oldValue; // Replace value
            }
        }

        z.parent = y;
        if (y == null) {
            root = z; // Tree was empty
        } else {
            int cmp = order.compare(z.key, y.key);
            if (cmp < 0) {
                y.left = z;
            } else {
                y.right = z;
            }
        }

        z.left = null;
        z.right = null;
        z.red = true;

        while (z != root && z.parent.red) {
            z.updateColor();
        }

        return null; // Inserted new key-value pair
    }

    public V get(K key) {
        Node node = root.find(key);
        return (node != null) ? node.value : null;
    }

    public V remove(K key) {
        Node node = root.find(key);
        if (node == null) return null;
        V oldValue = node.value;
        node.remove();
        return oldValue;
    }

    public boolean containsKey(K key) {
        Node node = root.find(key);
        return (node != null);
    }

    public int size() {
        AtomicInteger count = new AtomicInteger(0);
        inorderTraversal(root, count);
        return count.get();
    }

    private void inorderTraversal(Node node, AtomicInteger count) {
        if (node != null) {
            inorderTraversal(node.left, count);
            count.incrementAndGet();
            inorderTraversal(node.right, count);
        }
    }

    public Entry<K, V> entryAt(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
        return findEntryAt(root, index);
    }

    private Entry<K, V> findEntryAt(Node node, int index) {
        if (node == null) return null;
        int leftCount = sizeOfNode(node.left);
        if (leftCount == index) return new Entry<>(node.key, node.value);
        else if (leftCount > index) return findEntryAt(node.left, index);
        else return findEntryAt(node.right, index - leftCount - 1);
    }

    private int sizeOfNode(Node node) {
        if (node == null) return 0;
        return sizeOfNode(node.left) + sizeOfNode(node.right) + 1;
    }

    public int rank(K key) {
        if (key == null) throw new NullPointerException();
        Node node = root.find(key);
        return rank(root, node);
    }

    private int rank(Node x, Node target) {
        if (x == null) return 0;
        int cmp = order.compare(target.key, x.key);
        if (cmp < 0) return rank(x.left, target);
        else if (cmp > 0) return sizeOfNode(x.left) + rank(x.right, target) + 1;
        else return sizeOfNode(x.left);
    }

    public Snapshot<K, V> snapshot() {
        return new Snapshot<>(this);
    }
}

@Entry.ThreadSafe
class Entry<K, V> {
    @interface ThreadSafe {}

    private K key;
    private V value;

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

@Snapshot.ThreadSafe
class Snapshot<K, V> {
    @interface ThreadSafe {}

    private TreeDictionary<K, V> tree;

    public Snapshot(TreeDictionary<K, V> tree) {
        this.tree = new TreeDictionary<>(tree.order);
        tree.inorderTraversal(tree.root, (AtomicInteger count) -> {
            tree.put(tree.entryAt(count.get().key()).key(), tree.entryAt(count.get().key()).value());
        });
    }

    public int size() {
        return tree.size();
    }

    public V get(K key) {
        return tree.get(key);
    }

    public TreeDictionary.Entry<K, V> entryAt(int index) {
        return tree.entryAt(index);
    }
}
