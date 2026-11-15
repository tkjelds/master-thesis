@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

    private static final int DEFAULT_MINIMUM_DEGREE = 2;
    private Node<T> root;
    private Order<T> order;
    private int minimumDegree;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || minimumDegree > Integer.MAX_VALUE / (2 * DEFAULT_MINIMUM_DEGREE)) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        this.minimumDegree = minimumDegree;
        this.order = order;
        root = new Node<>(true);
    }

    public boolean add(T item) {
        synchronized (this) {
            if (root.keys.length == 2 * minimumDegree - 1) {
                Node<T> newRoot = new Node<>(false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                int i = order.compare(item, newRoot.keys[0]) < 0 ? 0 : 1;
                boolean result = newRoot.children[i].add(item);
                root = newRoot;
                return result;
            } else {
                return root.add(item);
            }
        }
    }

    public boolean remove(T item) {
        synchronized (this) {
            boolean result = root.remove(item);
            if (root.keys.length == 0 && !root.isLeaf) {
                root = root.children[0];
            }
            return result;
        }
    }

    public boolean contains(T item) {
        synchronized (this) {
            return root.contains(item);
        }
    }

    public T first() {
        synchronized (this) {
            if (root.isLeaf && root.keys.length == 0) {
                throw new IllegalStateException("Empty set");
            }
            Node<T> current = root;
            while (!current.isLeaf) {
                current = current.children[0];
            }
            return current.keys[0];
        }
    }

    public T last() {
        synchronized (this) {
            if (root.isLeaf && root.keys.length == 0) {
                throw new IllegalStateException("Empty set");
            }
            Node<T> current = root;
            while (!current.isLeaf) {
                current = current.children[current.children.length - 1];
            }
            return current.keys[current.keys.length - 1];
        }
    }

    public int size() {
        synchronized (this) {
            return root.size();
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            List<T> result = new ArrayList<>();
            root.snapshot(result);
            return result.toArray();
        }
    }

    private void splitChild(Node<T> parentNode, int index) {
        Node<T> childNode = parentNode.children[index];
        Node<T> newNode = new Node<>(childNode.isLeaf);
        parentNode.keys[index] = childNode.keys[minimumDegree - 1];
        System.arraycopy(childNode.keys, minimumDegree, newNode.keys, 0, minimumDegree - 1);
        if (!childNode.isLeaf) {
            System.arraycopy(childNode.children, minimumDegree, newNode.children, 0, minimumDegree);
        }
        childNode.keys = Arrays.copyOf(childNode.keys, minimumDegree - 1);
        if (!childNode.isLeaf) {
            childNode.children = Arrays.copyOf(childNode.children, minimumDegree);
        }
        parentNode.children[index + 1] = newNode;
        if (parentNode.isLeaf) {
            parentNode.children = Arrays.copyOf(parentNode.children, parentNode.children.length + 1);
        }
    }

    private static class Node<T> {
        T[] keys;
        Node<T>[] children;
        boolean isLeaf;

        Node(boolean isLeaf) {
            this.isLeaf = isLeaf;
            keys = (T[]) new Comparable[2 * minimumDegree - 1];
            children = isLeaf ? null : (Node<T>[]) new Node[2 * minimumDegree];
        }

        boolean add(T item) {
            int i = keys.length - 1;
            if (isLeaf) {
                while (i >= 0 && order.compare(keys[i], item) > 0) {
                    keys[i + 1] = keys[i];
                    i--;
                }
                keys[i + 1] = item;
                return true;
            } else {
                while (i >= 0 && order.compare(keys[i], item) > 0) {
                    i--;
                }
                i++;
                if (children[i].keys.length == 2 * minimumDegree - 1) {
                    splitChild(this, i);
                    if (order.compare(keys[i], item) < 0) {
                        i++;
                    }
                }
                return children[i].add(item);
            }
        }

        boolean remove(T item) {
            int i = keys.length - 1;
            while (i >= 0 && order.compare(keys[i], item) > 0) {
                i--;
            }
            if (i >= 0 && order.compare(keys[i], item) == 0) {
                return true;
            } else if (!isLeaf) {
                boolean result = children[i].remove(item);
                if (children[i].keys.length < minimumDegree) {
                    restoreChild(this, i);
                }
                return result;
            } else {
                return false;
            }
        }

        boolean contains(T item) {
            int i = 0;
            while (i < keys.length && order.compare(keys[i], item) < 0) {
                i++;
            }
            if (i < keys.length && order.compare(keys[i], item) == 0) {
                return true;
            } else if (isLeaf) {
                return false;
            } else {
                return children[i].contains(item);
            }
        }

        int size() {
            if (isLeaf) {
                return keys.length;
            } else {
                int size = 0;
                for (Node<T> child : children) {
                    size += child.size();
                }
                return size + keys.length;
            }
        }

        void snapshot(List<T> list) {
            if (isLeaf) {
                for (T key : keys) {
                    list.add(key);
                }
            } else {
                for (int i = 0; i < keys.length; i++) {
                    children[i].snapshot(list);
                    list.add(keys[i]);
                }
                children[keys.length].snapshot(list);
            }
        }

        private void restoreChild(Node<T> parentNode, int index) {
            if (index != 0 && parentNode.children[index - 1].keys.length > minimumDegree) {
                borrowFromLeft(parentNode, index);
            } else if (index < parentNode.keys.length && parentNode.children[index + 1].keys.length > minimumDegree) {
                borrowFromRight(parentNode, index);
            } else if (index != 0) {
                mergeWithLeft(parentNode, index);
            } else {
                mergeWithRight(parentNode, index);
            }
        }

        private void borrowFromLeft(Node<T> parentNode, int index) {
            Node<T> leftSibling = parentNode.children[index - 1];
            System.arraycopy(keys, index - 1, keys, index, keys.length - index);
            keys[index - 1] = parentNode.keys[index - 1];
            System.arraycopy(leftSibling.children, leftSibling.children.length - 1, children, index, children.length - index);
            children[index].children = Arrays.copyOf(leftSibling.children, leftSibling.children.length - 1);
            parentNode.keys[index - 1] = leftSibling.keys[leftSibling.keys.length - 1];
            leftSibling.keys = Arrays.copyOf(leftSibling.keys, leftSibling.keys.length - 1);
            if (!leftSibling.isLeaf) {
                leftSibling.children = Arrays.copyOf(leftSibling.children, leftSibling.children.length - 1);
            }
        }

        private void borrowFromRight(Node<T> parentNode, int index) {
            Node<T> rightSibling = parentNode.children[index + 1];
            keys[keys.length] = parentNode.keys[index];
            keys[keys.length] = rightSibling.keys[0];
            System.arraycopy(rightSibling.children, 1, children, index + 2, rightSibling.children.length - 1);
            if (!rightSibling.isLeaf) {
                children[keys.length + 2] = rightSibling.children[0];
            }
            rightSibling.keys = Arrays.copyOfRange(rightSibling.keys, 1, rightSibling.keys.length);
            if (!rightSibling.isLeaf) {
                rightSibling.children = Arrays.copyOfRange(rightSibling.children, 1, rightSibling.children.length);
            }
            parentNode.keys[index] = keys[keys.length];
            keys[keys.length] = null;
        }

        private void mergeWithLeft(Node<T> parentNode, int index) {
            Node<T> leftSibling = parentNode.children[index - 1];
            leftSibling.keys[leftSibling.keys.length] = keys[index - 1];
            System.arraycopy(children, index, leftSibling.children, leftSibling.keys.length + 1, children.length - index);
            for (T key : keys) {
                leftSibling.keys[leftSibling.keys.length] = key;
                leftSibling.children[leftSibling.keys.length] = children[index];
                index++;
            }
            parentNode.keys[index - 1] = null;
            System.arraycopy(parentNode.children, index, parentNode.children, index - 1, parentNode.children.length - index);
            parentNode.children[parentNode.children.length - 1] = null;
        }

        private void mergeWithRight(Node<T> parentNode, int index) {
            Node<T> rightSibling = parentNode.children[index + 1];
            keys[keys.length] = parentNode.keys[index];
            for (T key : rightSibling.keys) {
                keys[keys.length] = key;
                children[keys.length] = rightSibling.children[0];
                index++;
            }
            parentNode.keys[index] = null;
            System.arraycopy(parentNode.children, index + 2, parentNode.children, index + 1, parentNode.children.length - index - 2);
            parentNode.children[parentNode.children.length - 2] = null;
        }
    }

    public interface Order<T> {
        int compare(T left, T right);
    }
}
