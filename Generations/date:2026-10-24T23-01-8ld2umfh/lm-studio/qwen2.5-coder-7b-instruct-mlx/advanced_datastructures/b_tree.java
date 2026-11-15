interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
class BTree<T> {
    @interface ThreadSafe {}

    private final Node root;
    private final int minimumDegree;
    private final Order<T> order;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || order == null) {
            throw new IllegalArgumentException("Invalid minimum degree or order");
        }
        this.minimumDegree = minimumDegree;
        this.order = order;
        this.root = new Node(true);
    }

    @SuppressWarnings("unchecked")
    public boolean add(T item) {
        synchronized (root) {
            if (contains(item)) {
                return false;
            }
            boolean added = root.insert(item);
            if (root.isFull()) {
                Node newRoot = new Node(false);
                newRoot.children[0] = root;
                newRoot.splitChild(0, root);
                root = newRoot;
            }
            return added;
        }
    }

    public boolean remove(T item) {
        synchronized (root) {
            if (!contains(item)) {
                return false;
            }
            boolean removed = root.remove(item);
            if (root.isUnderflow() && !root.isLeaf()) {
                Node newRoot = root.children[0];
                for (int i = 1; i <= minimumDegree - 1; i++) {
                    newRoot.children[i] = root.children[i];
                }
                newRoot.setKeys(0, root.keys);
                root = newRoot;
            }
            return removed;
        }
    }

    public boolean contains(T item) {
        synchronized (root) {
            return root.contains(item);
        }
    }

    public T first() {
        synchronized (root) {
            if (root.isLeaf()) {
                if (root.keys.length == 0) {
                    throw new IllegalStateException("Empty tree");
                }
                return root.keys[0];
            } else {
                Node current = root;
                while (!current.isLeaf()) {
                    current = current.children[0];
                }
                return current.keys[0];
            }
        }
    }

    public T last() {
        synchronized (root) {
            if (root.isLeaf()) {
                if (root.keys.length == 0) {
                    throw new IllegalStateException("Empty tree");
                }
                return root.keys[root.keys.length - 1];
            } else {
                Node current = root;
                while (!current.isLeaf()) {
                    current = current.children[current.keys.length];
                }
                return current.keys[current.keys.length - 1];
            }
        }
    }

    public int size() {
        synchronized (root) {
            return root.size();
        }
    }

    @SuppressWarnings("unchecked")
    public Object[] snapshot() {
        synchronized (root) {
            Object[] result = new Object[root.size()];
            int index = 0;
            Node current = root;
            while (!current.isLeaf()) {
                for (int i = 0; i < current.children.length - 1; i++) {
                    appendRange(result, index, current.children[i].keys);
                    index += current.children[i].keys.length;
                }
            }
            appendRange(result, index, current.keys);
            return result;
        }

        private void appendRange(Object[] array, int offset, T[] keys) {
            for (int i = 0; i < keys.length; i++) {
                array[offset + i] = keys[i];
            }
        }
    }

    private static class Node {
        private final boolean isLeaf;
        private final T[] keys;
        private final Node[] children;

        @SuppressWarnings("unchecked")
        public Node(boolean isLeaf) {
            this.isLeaf = isLeaf;
            int capacity = 2 * minimumDegree - 1;
            this.keys = (T[]) new Object[capacity];
            this.children = new Node[capacity + 1];
        }

        private boolean isFull() {
            return keys.length == keys.length;
        }

        private boolean isUnderflow() {
            return keys.length < minimumDegree - 1;
        }

        private boolean isLeaf() {
            return isLeaf;
        }

        public boolean insert(T item) {
            int index = keys.length - 1;
            if (isLeaf()) {
                while (index >= 0 && order.compare(keys[index], item) > 0) {
                    keys[index + 1] = keys[index];
                    index--;
                }
                keys[index + 1] = item;
            } else {
                while (index >= 0 && order.compare(keys[index], item) > 0) {
                    index--;
                }
                Node child = children[index + 1];
                if (child.isFull()) {
                    splitChild(index + 1, child);
                    if (order.compare(keys[index + 1], item) < 0) {
                        index++;
                    }
                }
                return child.insert(item);
            }
            return true;
        }

        public boolean remove(T item) {
            int index = findKey(item);
            if (index < 0 || !isLeaf()) {
                Node child = children[index];
                if (!child.isLeaf()) {
                    T predecessor = findPredecessor(child);
                    keys[index] = predecessor;
                    return child.remove(predecessor);
                } else {
                    if (child.keys.length < minimumDegree) {
                        borrowFromOrMerge(index, child);
                    }
                    return child.remove(item);
                }
            } else {
                System.arraycopy(keys, index + 1, keys, index, keys.length - index - 1);
                return true;
            }
        }

        private void borrowFromOrMerge(int index, Node child) {
            if (index > 0 && children[index - 1].keys.length > minimumDegree) {
                borrowFromLeft(index, child);
            } else if (index < children.length - 1 && children[index + 1].keys.length > minimumDegree) {
                borrowFromRight(index, child);
            } else {
                mergeWithLeftOrRight(index, child);
            }
        }

        private void borrowFromLeft(int index, Node child) {
            Node sibling = children[index - 1];
            System.arraycopy(child.keys, 0, child.keys, 1, child.keys.length);
            if (!child.isLeaf()) {
                System.arraycopy(child.children, 0, child.children, 1, child.children.length);
            }
            child.keys[0] = keys[index - 1];
            Node siblingChild = sibling.children[sibling.keys.length];
            for (int i = child.keys.length; i > 0; i--) {
                child.keys[i] = child.keys[i - 1];
            }
            if (!child.isLeaf()) {
                for (int i = child.children.length; i > 0; i--) {
                    child.children[i] = child.children[i - 1];
                }
            }
            child.children[0] = siblingChild;
            keys[index - 1] = sibling.keys[sibling.keys.length - 1];
            sibling.setKeys(0, null);
            if (!sibling.isLeaf()) {
                System.arraycopy(sibling.children, 0, sibling.children, 1, sibling.children.length);
            }
        }

        private void borrowFromRight(int index, Node child) {
            Node sibling = children[index + 1];
            child.keys[child.keys.length] = keys[index];
            if (!child.isLeaf()) {
                child.children[child.children.length] = sibling.children[0];
            }
            for (int i = 1; i < sibling.keys.length; i++) {
                sibling.children[i - 1] = sibling.keys[i];
            }
            for (int i = 0; i < sibling.children.length - 1; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.keys[0] = siblingChild.keys[siblingChild.keys.length - 1];
            System.arraycopy(child.keys, child.keys.length, keys, index, keys.length - index);
            if (!child.isLeaf()) {
                System.arraycopy(child.children, child.children.length, sibling.children, 0, sibling.children.length - 1);
            }
            parent.remove(index + 1, this);
            child.keys[keys.length - 1] = null;
        }

        private void mergeWithLeftOrRight(int index, Node child) {
            if (index > 0) {
                mergeWithLeft(index - 1, child);
            } else {
                Node sibling = children[index + 1];
                System.arraycopy(child.keys, 0, keys, index, child.keys.length);
                if (!child.isLeaf()) {
                    System.arraycopy(child.children, 0, children, index, child.children.length);
                }
                for (int i = 0; i < sibling.keys.length; i++) {
                    child.keys[child.keys.length + i] = sibling.keys[i];
                }
                if (!sibling.isLeaf()) {
                    System.arraycopy(sibling.children, 0, child.children, child.keys.length + 1, sibling.children.length);
                }
                parent.remove(index + 1, this);
            }
        }

        private T findPredecessor(Node child) {
            while (!child.isLeaf()) {
                child = child.children[child.keys.length];
            }
            return child.keys[child.keys.length - 1];
        }

        @SuppressWarnings("unchecked")
        private int findKey(T item) {
            for (int i = 0; i < keys.length; i++) {
                if (order.compare(keys[i], item) == 0) {
                    return i;
                }
            }
            return -1;
        }

        @SuppressWarnings("unchecked")
        private void splitChild(int index, Node child) {
            Node sibling = new Node(child.isLeaf());
            int siblingCapacity = minimumDegree - 1;
            System.arraycopy(child.keys, siblingCapacity + 1, sibling.keys, 0, child.keys.length - siblingCapacity - 1);
            if (!child.isLeaf()) {
                System.arraycopy(child.children, siblingCapacity + 1, sibling.children, 0, child.children.length - siblingCapacity - 1);
            }
            keys[index] = null;
            System.arraycopy(keys, index + 1, keys, index + 2, child.keys.length - siblingCapacity - 1);
            System.arraycopy(children, index + 1, children, index + 2, child.children.length - siblingCapacity - 1);
            keys[index + 1] = child.keys[siblingCapacity];
            if (!child.isLeaf()) {
                children[index + 1] = child.children[siblingCapacity];
            }
            child.setKeys(0, null);
            if (!child.isLeaf()) {
                System.arraycopy(child.children, 0, child.children, 1, siblingCapacity);
            }
            children[index + 2] = sibling;
        }

        @SuppressWarnings("unchecked")
        private void setKeys(int index, T key) {
            keys[index] = key;
        }

        @SuppressWarnings("unchecked")
        private int size() {
            if (isLeaf()) {
                return keys.length;
            } else {
                int size = 0;
                for (int i = 0; i < children.length; i++) {
                    size += children[i].size();
                }
                return size;
            }
        }

        @SuppressWarnings("unchecked")
        private boolean contains(T item) {
            int index = findKey(item);
            if (index >= 0) {
                return true;
            } else {
                Node current = children[findKey(item)];
                while (!current.isLeaf()) {
                    current = children[current.keys.length - 1];
                }
                return Arrays.asList(current.keys).contains(item);
            }
        }

        private void remove(int index, Node child) {
            if (index < 0 || index >= children.length) {
                return;
            }
            System.arraycopy(children, index + 1, children, index, children.length - index - 1);
            children[children.length - 1] = null;
        }
    }
}
