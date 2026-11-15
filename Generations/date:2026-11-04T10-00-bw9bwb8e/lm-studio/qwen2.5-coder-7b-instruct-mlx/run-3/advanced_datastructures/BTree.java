@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

    private static final int MIN_DEGREE = 2;
    private Node<T> root;
    private Order<T> order;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < MIN_DEGREE || minimumDegree >= Integer.MAX_VALUE / (2 * MIN_DEGREE)) {
            throw new IllegalArgumentException("Minimum degree must be at least 2 and not cause integer overflow");
        }
        this.order = order;
        this.root = new Node<>(true, minimumDegree);
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (root) {
            boolean result = addRecursive(root, item);
            if (result && root.isFull()) {
                Node<T> newRoot = new Node<>(false, order);
                newRoot.children[0] = root;
                root.splitChild(0, newRoot);
                if (order.compare(newRoot.children[0].keys[0], item) > 0) {
                    root = newRoot.children[0];
                } else {
                    root = newRoot.children[1];
                }
            }
            return result;
        }
    }

    private boolean addRecursive(Node<T> node, T item) {
        int i = order.upperBound(node.keys, 0, node.keyCount - 1, item);
        if (node.isLeaf) {
            for (; i < node.keyCount && order.compare(node.keys[i], item) <= 0; ++i);
            for (int j = node.keyCount; j > i; --j) {
                node.keys[j] = node.keys[j - 1];
            }
            node.keys[i] = item;
            ++node.keyCount;
            return true;
        } else {
            while (i < node.keyCount && order.compare(node.keys[i], item) <= 0) {
                ++i;
            }
            Node<T> child = node.children[i];
            if (child.isFull()) {
                child.splitChild(i, node);
                if (order.compare(child.keys[0], item) > 0) {
                    child = node.children[i];
                } else {
                    ++i;
                    child = node.children[i];
                }
            }
            return addRecursive(child, item);
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (root) {
            boolean result = removeRecursive(root, item);
            if (result && root.isLeaf && root.keyCount == 0) {
                root = null;
            }
            return result;
        }
    }

    private boolean removeRecursive(Node<T> node, T item) {
        int i = order.lowerBound(node.keys, 0, node.keyCount - 1, item);
        boolean found = i < node.keyCount && order.compare(node.keys[i], item) == 0;
        if (node.isLeaf) {
            if (found) {
                for (; i < node.keyCount - 1; ++i) {
                    node.keys[i] = node.keys[i + 1];
                }
                --node.keyCount;
            }
        } else {
            Node<T> child = node.children[i];
            if (child.keyCount < node.minimumDegree) {
                Node<T> sibling = getSibling(node, i);
                if (sibling.keyCount > node.minimumDegree) {
                    borrowFromSibling(child, sibling, i);
                } else {
                    mergeWithSibling(child, sibling, i);
                    child = sibling;
                    i = (i == 0) ? i : i - 1;
                }
            }
            found = removeRecursive(child, item);
        }
        return found;
    }

    private Node<T> getSibling(Node<T> node, int i) {
        return (i == 0) ? node.children[1] : node.children[i - 1];
    }

    private void borrowFromSibling(Node<T> child, Node<T> sibling, int i) {
        child.keys[child.keyCount] = node.keys[i];
        if (!child.isLeaf) {
            child.children[child.keyCount + 1] = sibling.children[0];
            for (int j = 1; j <= sibling.keyCount - 1; ++j) {
                sibling.children[j] = sibling.children[j + 1];
            }
        }
        child.keyCount++;
        sibling.keyCount--;
        node.keys[i] = sibling.keys[0];
    }

    private void mergeWithSibling(Node<T> child, Node<T> sibling, int i) {
        for (int j = child.keyCount; j > 0; --j) {
            child.keys[j] = child.keys[j - 1];
        }
        child.keys[0] = node.keys[i];
        if (!child.isLeaf) {
            for (int j = child.keyCount + 1; j <= child.keyCount + sibling.keyCount; ++j) {
                child.children[j] = sibling.children[j - child.keyCount - 1];
            }
        }
        for (int j = 0; j < sibling.keyCount; ++j) {
            child.keys[child.keyCount + j + 1] = sibling.keys[j];
        }
        child.keyCount += sibling.keyCount + 1;
        for (int j = i; j < node.keyCount - 1; ++j) {
            node.keys[j] = node.keys[j + 1];
        }
        for (int j = i + 1; j <= node.keyCount; ++j) {
            node.children[j] = node.children[j + 1];
        }
        --node.keyCount;
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (root) {
            return search(root, item);
        }
    }

    private boolean search(Node<T> node, T item) {
        int i = order.lowerBound(node.keys, 0, node.keyCount - 1, item);
        if (i < node.keyCount && order.compare(node.keys[i], item) == 0) {
            return true;
        } else if (node.isLeaf) {
            return false;
        } else {
            return search(node.children[i], item);
        }
    }

    public T first() {
        synchronized (root) {
            if (root == null || root.keyCount == 0) throw new IllegalStateException("Empty tree");
            Node<T> node = root;
            while (!node.isLeaf) {
                node = node.children[0];
            }
            return node.keys[0];
        }
    }

    public T last() {
        synchronized (root) {
            if (root == null || root.keyCount == 0) throw new IllegalStateException("Empty tree");
            Node<T> node = root;
            while (!node.isLeaf) {
                node = node.children[node.keyCount - 1];
            }
            return node.keys[node.keyCount - 1];
        }
    }

    public int size() {
        synchronized (root) {
            if (root == null) return 0;
            int count = root.keyCount + 1;
            for (int i = 0; i < root.keyCount; ++i) {
                count += sizeRecursive(root.children[i]);
            }
            return count;
        }
    }

    private int sizeRecursive(Node<T> node) {
        if (node.isLeaf) return 1;
        int count = 0;
        for (int i = 0; i < node.keyCount; ++i) {
            count += sizeRecursive(node.children[i]);
        }
        return count;
    }

    public Object[] snapshot() {
        synchronized (root) {
            if (root == null) return new Object[0];
            List<Object> result = new ArrayList<>();
            inorderTraversal(root, result);
            return result.toArray();
        }
    }

    private void inorderTraversal(Node<T> node, List<Object> list) {
        if (node.isLeaf) {
            for (T key : node.keys) {
                list.add(key);
            }
        } else {
            for (int i = 0; i < node.keyCount; ++i) {
                inorderTraversal(node.children[i], list);
                list.add(node.keys[i]);
            }
            inorderTraversal(node.children[node.keyCount], list);
        }
    }

    private static class Node<T> {
        T[] keys;
        Node<T>[] children;
        boolean isLeaf;
        int keyCount;
        int minimumDegree;

        Node(boolean leaf, int minimumDegree) {
            this.keys = (T[]) new Comparable[2 * minimumDegree - 1];
            this.children = new Node[2 * minimumDegree];
            this.isLeaf = leaf;
            this.keyCount = 0;
            this.minimumDegree = minimumDegree;
        }

        boolean isFull() {
            return keyCount == 2 * minimumDegree - 1;
        }

        void splitChild(int i, Node<T> parent) {
            Node<T> sibling = new Node<>(isLeaf, minimumDegree);
            sibling.keyCount = minimumDegree - 1;
            for (int j = 0; j < minimumDegree - 1; ++j) {
                sibling.keys[j] = keys[j + minimumDegree];
            }
            if (!isLeaf) {
                for (int j = 0; j < minimumDegree; ++j) {
                    sibling.children[j] = children[j + minimumDegree];
                }
            }
            keys[minimumDegree - 1] = null;
            for (int j = keyCount; j > i; --j) {
                children[j + 1] = children[j];
            }
            children[i + 1] = sibling;
            keyCount--;
        }

        int upperBound(T[] arr, int fromIndex, int toIndex, T item) {
            int lo = fromIndex;
            int hi = toIndex + 1;
            while (lo < hi) {
                int mid = lo + (hi - lo) / 2;
                if (order.compare(arr[mid], item) > 0) {
                    hi = mid;
                } else if (order.compare(arr[mid], item) == 0) {
                    return mid;
                } else {
                    lo = mid + 1;
                }
            }
            return hi;
        }

        int lowerBound(T[] arr, int fromIndex, int toIndex, T item) {
            int lo = fromIndex;
            int hi = toIndex + 1;
            while (lo < hi) {
                int mid = lo + (hi - lo) / 2;
                if (order.compare(arr[mid], item) < 0) {
                    lo = mid + 1;
                } else if (order.compare(arr[mid], item) == 0) {
                    return mid;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    public interface Order<T> {
        int compare(T left, T right);
    }
}
