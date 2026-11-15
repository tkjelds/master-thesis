@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;

    private final Object lock = new Object();
    private Node first;
    private Node last;
    private int size;
    private Object[] buckets = new Object[INITIAL_CAPACITY];

    private final class Node {
        private T item;
        private int hash;
        private Node previous;
        private Node next;
        private Node bucketNext;

        private Node(T item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    public boolean add(T item) {
        synchronized (lock) {
            return addAt(size, item);
        }
    }

    public boolean addFirst(T item) {
        synchronized (lock) {
            return addAt(0, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException();
            }
            return addAt(index, item);
        }
    }

    private boolean addAt(int index, T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        int hash = item.hashCode();
        if (findNode(item, hash) != null) {
            return false;
        }

        Node successor = index == size ? null : nodeAt(index);
        Node node = new Node(item, hash);
        ensureCapacityForInsert();

        if (successor == null) {
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
        } else {
            node.next = successor;
            node.previous = successor.previous;
            if (successor.previous == null) {
                first = node;
            } else {
                successor.previous.next = node;
            }
            successor.previous = node;
        }

        int bucket = bucketIndex(hash, buckets.length);
        node.bucketNext = (Node) buckets[bucket];
        buckets[bucket] = node;
        size++;
        return true;
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            checkElementIndex(index);
            if (item == null) {
                throw new NullPointerException("item");
            }

            Node node = nodeAt(index);
            int hash = item.hashCode();
            Node existing = findNode(item, hash);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("An equal item is already present");
            }

            T previousItem = node.item;
            if (node.hash != hash) {
                removeFromIndex(node);
                node.item = item;
                node.hash = hash;
                int bucket = bucketIndex(hash, buckets.length);
                node.bucketNext = (Node) buckets[bucket];
                buckets[bucket] = node;
            } else {
                node.item = item;
            }
            return previousItem;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            Node node = nodeAt(index);
            T item = node.item;
            unlink(node);
            return item;
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            if (item == null) {
                throw new NullPointerException("item");
            }

            Node node = findNode(item, item.hashCode());
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            Node node = first;
            T item = node.item;
            unlink(node);
            return item;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            Node node = last;
            T item = node.item;
            unlink(node);
            return item;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            if (item == null) {
                throw new NullPointerException("item");
            }
            return findNode(item, item.hashCode()) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node node = first;
            for (int i = 0; i < size; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private Node findNode(T item, int hash) {
        int bucket = bucketIndex(hash, buckets.length);
        Node node = (Node) buckets[bucket];
        while (node != null) {
            if (node.hash == hash && node.item.equals(item)) {
                return node;
            }
            node = node.bucketNext;
        }
        return null;
    }

    private void ensureCapacityForInsert() {
        if (size + 1 <= buckets.length - (buckets.length >>> 2)) {
            return;
        }

        int newCapacity = buckets.length << 1;
        if (newCapacity <= buckets.length) {
            throw new OutOfMemoryError("Hash table capacity exceeded");
        }

        Object[] newBuckets = new Object[newCapacity];
        for (Node node = first; node != null; node = node.next) {
            int bucket = bucketIndex(node.hash, newCapacity);
            node.bucketNext = (Node) newBuckets[bucket];
            newBuckets[bucket] = node;
        }
        buckets = newBuckets;
    }

    private int bucketIndex(int hash, int capacity) {
        int spreadHash = hash ^ (hash >>> 16);
        return spreadHash & (capacity - 1);
    }

    private Node nodeAt(int index) {
        if (index < (size >>> 1)) {
            Node node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }

        Node node = last;
        for (int i = size - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void removeFromIndex(Node target) {
        int bucket = bucketIndex(target.hash, buckets.length);
        Node previous = null;
        Node node = (Node) buckets[bucket];

        while (node != null && node != target) {
            previous = node;
            node = node.bucketNext;
        }
        if (node == null) {
            throw new IllegalStateException("List node is missing from the hash index");
        }

        if (previous == null) {
            buckets[bucket] = node.bucketNext;
        } else {
            previous.bucketNext = node.bucketNext;
        }
        node.bucketNext = null;
    }

    private void unlink(Node node) {
        removeFromIndex(node);

        if (node.previous == null) {
            first = node.next;
        } else {
            node.previous.next = node.next;
        }
        if (node.next == null) {
            last = node.previous;
        } else {
            node.next.previous = node.previous;
        }

        node.previous = null;
        node.next = null;
        size--;
    }
}
