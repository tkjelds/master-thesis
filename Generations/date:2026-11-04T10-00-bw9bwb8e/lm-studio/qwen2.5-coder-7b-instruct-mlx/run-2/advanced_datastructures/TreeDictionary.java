import java.util.NoSuchElementException;

interface Order<K> {
    int compare(K left, K right);
}

@Entry.ThreadSafe
class Entry<K, V> {
    @interface ThreadSafe {}

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

@Snapshot.ThreadSafe
class Snapshot<K, V> {
    @interface ThreadSafe {}

    private final int size;
    private final Entry<K, V>[] entries;

    public Snapshot(int size, Entry<K, V>[] entries) {
        this.size = size;
        this.entries = entries;
    }

    public int size() {
        return size;
    }

    public V get(K key) {
        for (Entry<K, V> entry : entries) {
            if (key.equals(entry.key())) {
                return entry.value();
            }
        }
        return null;
    }

    public Entry<K, V> entryAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return entries[index];
    }
}

@TreeDictionary.ThreadSafe
class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private final Order<K> order;
    private Node<K, V> root;
    private volatile int size;

    public TreeDictionary(Order<K> order) {
        this.order = order;
        this.root = null;
        this.size = 0;
    }

    private static class Node<K, V> {
        final K key;
        volatile V value;
        volatile Node<K, V> left, right, parent;
        boolean color;

        public Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.left = null;
            this.right = null;
            this.parent = null;
            this.color = true; // Red
        }
    }

    private void leftRotate(Node<K, V> x) {
        Node<K, V> y = x.right;
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

    private void rightRotate(Node<K, V> y) {
        Node<K, V> x = y.left;
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

    private void fixInsertion(Node<K, V> k) {
        while (k != null && k.parent.color == true) {
            if (k.parent == k.parent.parent.left) {
                Node<K, V> u = k.parent.parent.right;
                if (u != null && u.color == true) {
                    u.color = false;
                    k.parent.color = false;
                    k.parent.parent.color = true;
                    k = k.parent.parent;
                } else {
                    if (k == k.parent.right) {
                        k = k.parent;
                        leftRotate(k);
                    }
                    k.parent.color = false;
                    k.parent.parent.color = true;
                    rightRotate(k.parent.parent);
                }
            } else {
                Node<K, V> u = k.parent.parent.left;
                if (u != null && u.color == true) {
                    u.color = false;
                    k.parent.color = false;
                    k.parent.parent.color = true;
                    k = k.parent.parent;
                } else {
                    if (k == k.parent.left) {
                        k = k.parent;
                        rightRotate(k);
                    }
                    k.parent.color = false;
                    k.parent.parent.color = true;
                    leftRotate(k.parent.parent);
                }
            }
        }
        root.color = false;
    }

    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("Null keys and values are not allowed.");
        Node<K, V> z = new Node<>(key, value);
        Node<K, V> y = null;
        Node<K, V> x = root;

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
                return oldValue;
            }
        }

        z.parent = y;
        if (y == null) {
            root = z;
        } else if (order.compare(z.key, y.key) < 0) {
            y.left = z;
        } else {
            y.right = z;
        }

        z.left = null;
        z.right = null;
        z.color = true;

        fixInsertion(z);

        size++;
        return null;
    }

    public V get(K key) {
        Node<K, V> x = root;
        while (x != null) {
            int cmp = order.compare(key, x.key);
            if (cmp < 0) {
                x = x.left;
            } else if (cmp > 0) {
                x = x.right;
            } else {
                return x.value;
            }
        }
        return null;
    }

    public V remove(K key) {
        Node<K, V> z = root;
        while (z != null) {
            int cmp = order.compare(key, z.key);
            if (cmp < 0) {
                z = z.left;
            } else if (cmp > 0) {
                z = z.right;
            } else {
                break;
            }
        }

        if (z == null) return null;

        V oldValue = z.value;
        Node<K, V> y = (z.left == null || z.right == null) ? z : getSuccessor(z);
        Node<K, V> x = (y.left != null) ? y.left : y.right;

        if (x != null) {
            x.parent = y.parent;
        }

        if (y.parent == null) {
            root = x;
        } else if (y == y.parent.left) {
            y.parent.left = x;
        } else {
            y.parent.right = x;
        }

        if (y != z) {
            z.key = y.key;
            z.value = y.value;
        }

        if (y.color == false) {
            fixDeletion(x);
        }

        size--;
        return oldValue;
    }

    private void fixDeletion(Node<K, V> x) {
        while (x != root && x.color == false) {
            if (x == x.parent.left) {
                Node<K, V> sib = x.parent.right;
                if (sib.color == true) {
                    sib.color = false;
                    x.parent.color = true;
                    leftRotate(x.parent);
                    sib = x.parent.right;
                }

                if (sib.left.color == false && sib.right.color == false) {
                    sib.color = true;
                    x = x.parent;
                } else {
                    if (sib.right.color == false) {
                        sib.left.color = false;
                        sib.color = true;
                        rightRotate(sib);
                        sib = x.parent.right;
                    }

                    sib.color = x.parent.color;
                    x.parent.color = false;
                    sib.right.color = false;
                    leftRotate(x.parent);
                    x = root;
                }
            } else {
                Node<K, V> sib = x.parent.left;
                if (sib.color == true) {
                    sib.color = false;
                    x.parent.color = true;
                    rightRotate(x.parent);
                    sib = x.parent.left;
                }

                if (sib.right.color == false && sib.left.color == false) {
                    sib.color = true;
                    x = x.parent;
                } else {
                    if (sib.left.color == false) {
                        sib.right.color = false;
                        sib.color = true;
                        leftRotate(sib);
                        sib = x.parent.left;
                    }

                    sib.color = x.parent.color;
                    x.parent.color = false;
                    sib.left.color = false;
                    rightRotate(x.parent);
                    x = root;
                }
            }
        }

        if (x != null) {
            x.color = false;
        }
    }

    private Node<K, V> getSuccessor(Node<K, V> z) {
        if (z.right != null) {
            Node<K, V> y = z.right;
            while (y.left != null) {
                y = y.left;
            }
            return y;
        } else {
            Node<K, V> y = z.parent;
            while (y != null && z == y.right) {
                z = y;
                y = y.parent;
            }
            return y;
        }
    }

    public boolean containsKey(K key) {
        Node<K, V> x = root;
        while (x != null) {
            int cmp = order.compare(key, x.key);
            if (cmp < 0) {
                x = x.left;
            } else if (cmp > 0) {
                x = x.right;
            } else {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return size;
    }

    public Entry<K, V> entryAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        Node<K, V> current = root;
        int count = 0;

        while (current != null) {
            int leftSize = (current.left != null) ? current.left.size : 0;
            if (index == count + leftSize) {
                return new Entry<>(current.key, current.value);
            } else if (index < count + leftSize) {
                current = current.left;
                count += 1;
            } else {
                current = current.right;
                count += 1 + leftSize;
            }
        }

        throw new NoSuchElementException("Invalid index: " + index);
    }

    public int rank(K key) {
        Node<K, V> x = root;
        int count = 0;

        while (x != null) {
            int cmp = order.compare(key, x.key);
            if (cmp < 0) {
                x = x.left;
            } else if (cmp > 0) {
                count += ((x.left != null) ? x.left.size : 0) + 1;
                x = x.right;
            } else {
                return count + ((x.left != null) ? x.left.size : 0);
            }
        }

        return count;
    }

    public Snapshot<K, V> snapshot() {
        int s = size();
        Entry<K, V>[] entries = new Entry[s];
        traverseInOrder(root, entries);
        return new Snapshot<>(s, entries);
    }

    private void traverseInOrder(Node<K, V> node, Entry<K, V>[] entries) {
        if (node == null) return;

        traverseInOrder(node.left, entries);
        int index = size - s;
        entries[index] = new Entry<>(node.key, node.value);
        s--;
        traverseInOrder(node.right, entries);
    }
}
