@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private static final class Node {
        Object item;
        int hash;
        Node previous;
        Node next;
        Node bucketNext;

        Node(Object item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();
    private Node[] buckets = new Node[INITIAL_CAPACITY];
    private Node first;
    private Node last;
    private int size;

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return insertNewNode(size, item, spread(item.hashCode()));
        }
    }

    public boolean addFirst(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return insertNewNode(0, item, spread(item.hashCode()));
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkPositionIndex(index);
            return insertNewNode(index, item, spread(item.hashCode()));
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return (T) nodeAt(index).item;
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkElementIndex(index);

            Node target = nodeAt(index);
            int newHash = spread(item.hashCode());
            Node existing = findNode(item, newHash);
            if (existing != null && existing != target) {
                throw new IllegalArgumentException("An equal item is already present");
            }

            Object previous = target.item;
            unlinkFromHashTable(target);
            target.item = item;
            target.hash = newHash;
            linkIntoHashTable(target);
            return (T) previous;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            Node node = nodeAt(index);
            Object item = node.item;
            unlinkNode(node);
            return (T) item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int hash = spread(item.hashCode());
            Node node = findNode(item, hash);
            if (node == null) {
                return false;
            }
            unlinkNode(node);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("The list is empty");
            }
            Node node = first;
            Object item = node.item;
            unlinkNode(node);
            return (T) item;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("The list is empty");
            }
            Node node = last;
            Object item = node.item;
            unlinkNode(node);
            return (T) item;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return findNode(item, spread(item.hashCode())) != null;
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
            for (int i = 0; i < result.length; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private boolean insertNewNode(int index, Object item, int hash) {
        if (findNode(item, hash) != null) {
            return false;
        }

        ensureCapacityForOne();
        Node node = new Node(item, hash);
        Node successor = index == size ? null : nodeAt(index);
        Node predecessor = successor == null ? last : successor.previous;

        node.previous = predecessor;
        node.next = successor;
        if (predecessor == null) {
            first = node;
        } else {
            predecessor.next = node;
        }
        if (successor == null) {
            last = node;
        } else {
            successor.previous = node;
        }

        linkIntoHashTable(node);
        size++;
        return true;
    }

    private Node findNode(Object item, int hash) {
        Node node = buckets[hash & (buckets.length - 1)];
        while (node != null) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
            node = node.bucketNext;
        }
        return null;
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

    private void unlinkNode(Node node) {
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

        unlinkFromHashTable(node);
        size--;

        node.previous = null;
        node.next = null;
        node.bucketNext = null;
    }

    private void linkIntoHashTable(Node node) {
        int bucket = node.hash & (buckets.length - 1);
        node.bucketNext = buckets[bucket];
        buckets[bucket] = node;
    }

    private void unlinkFromHashTable(Node target) {
        int bucket = target.hash & (buckets.length - 1);
        Node previous = null;
        Node node = buckets[bucket];

        while (node != null) {
            if (node == target) {
                if (previous == null) {
                    buckets[bucket] = node.bucketNext;
                } else {
                    previous.bucketNext = node.bucketNext;
                }
                node.bucketNext = null;
                return;
            }
            previous = node;
            node = node.bucketNext;
        }
    }

    private void ensureCapacityForOne() {
        int threshold = buckets.length - (buckets.length >>> 2);
        if (size < threshold || buckets.length >= MAX_CAPACITY) {
            return;
        }

        Node[] newBuckets = new Node[buckets.length << 1];
        for (int i = 0; i < buckets.length; i++) {
            Node node = buckets[i];
            while (node != null) {
                Node next = node.bucketNext;
                int bucket = node.hash & (newBuckets.length - 1);
                node.bucketNext = newBuckets[bucket];
                newBuckets[bucket] = node;
                node = next;
            }
        }
        buckets = newBuckets;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }
}
