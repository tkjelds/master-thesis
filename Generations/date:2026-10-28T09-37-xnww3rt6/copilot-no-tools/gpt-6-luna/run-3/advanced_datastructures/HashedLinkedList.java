@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;

    private static final class Node<E> {
        E item;
        Node<E> previous;
        Node<E> next;
        Node<E> hashNext;
        int hash;

        Node(E item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();
    private Node<T> first;
    private Node<T> last;
    private Node<T>[] buckets;
    private int size;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        buckets = (Node<T>[]) new Node[INITIAL_CAPACITY];
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int hash = item.hashCode();
            if (find(item, hash) != null) {
                return false;
            }
            ensureCapacityForOneMore();
            Node<T> node = new Node<T>(item, hash);
            appendNode(node);
            addToHash(node);
            size++;
            return true;
        }
    }

    public boolean addFirst(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int hash = item.hashCode();
            if (find(item, hash) != null) {
                return false;
            }
            ensureCapacityForOneMore();
            Node<T> node = new Node<T>(item, hash);
            prependNode(node);
            addToHash(node);
            size++;
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            int hash = item.hashCode();
            if (find(item, hash) != null) {
                return false;
            }
            ensureCapacityForOneMore();

            Node<T> node = new Node<T>(item, hash);
            if (index == size) {
                appendNode(node);
            } else if (index == 0) {
                prependNode(node);
            } else {
                Node<T> target = nodeAt(index);
                node.previous = target.previous;
                node.next = target;
                target.previous.next = node;
                target.previous = node;
            }
            addToHash(node);
            size++;
            return true;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<T> node = nodeAt(index);
            int hash = item.hashCode();
            Node<T> existing = find(item, hash);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("item already exists");
            }

            T previousItem = node.item;
            if (existing == node) {
                node.item = item;
            } else {
                removeFromHash(node);
                node.item = item;
                node.hash = hash;
                addToHash(node);
            }
            return previousItem;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            Node<T> node = nodeAt(index);
            T item = node.item;
            unlinkNode(node);
            return item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<T> node = find(item, item.hashCode());
            if (node == null) {
                return false;
            }
            unlinkNode(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("list is empty");
            }
            T item = first.item;
            unlinkNode(first);
            return item;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("list is empty");
            }
            T item = last.item;
            unlinkNode(last);
            return item;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return find(item, item.hashCode()) != null;
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
            Node<T> node = first;
            for (int i = 0; i < result.length; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private Node<T> nodeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        if (index < size / 2) {
            Node<T> node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }
        Node<T> node = last;
        for (int i = size - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
    }

    private Node<T> find(T item, int hash) {
        int bucket = bucketIndex(hash, buckets.length);
        for (Node<T> node = buckets[bucket]; node != null; node = node.hashNext) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
        }
        return null;
    }

    private void appendNode(Node<T> node) {
        node.previous = last;
        if (last == null) {
            first = node;
        } else {
            last.next = node;
        }
        last = node;
    }

    private void prependNode(Node<T> node) {
        node.next = first;
        if (first == null) {
            last = node;
        } else {
            first.previous = node;
        }
        first = node;
    }

    private void unlinkNode(Node<T> node) {
        removeFromHash(node);

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
        node.hashNext = null;
        size--;
    }

    private void addToHash(Node<T> node) {
        int bucket = bucketIndex(node.hash, buckets.length);
        node.hashNext = buckets[bucket];
        buckets[bucket] = node;
    }

    private void removeFromHash(Node<T> node) {
        int bucket = bucketIndex(node.hash, buckets.length);
        Node<T> current = buckets[bucket];
        Node<T> previous = null;

        while (current != null) {
            if (current == node) {
                if (previous == null) {
                    buckets[bucket] = current.hashNext;
                } else {
                    previous.hashNext = current.hashNext;
                }
                return;
            }
            previous = current;
            current = current.hashNext;
        }
    }

    private void ensureCapacityForOneMore() {
        if (size + 1 <= buckets.length - (buckets.length >>> 2)) {
            return;
        }

        int newCapacity = buckets.length << 1;
        if (newCapacity <= buckets.length) {
            throw new OutOfMemoryError("hash table is too large");
        }

        @SuppressWarnings("unchecked")
        Node<T>[] newBuckets = (Node<T>[]) new Node[newCapacity];

        for (int i = 0; i < buckets.length; i++) {
            Node<T> node = buckets[i];
            while (node != null) {
                Node<T> next = node.hashNext;
                int bucket = bucketIndex(node.hash, newCapacity);
                node.hashNext = newBuckets[bucket];
                newBuckets[bucket] = node;
                node = next;
            }
        }
        buckets = newBuckets;
    }

    private static int bucketIndex(int hash, int capacity) {
        int spreadHash = hash ^ (hash >>> 16);
        return spreadHash & (capacity - 1);
    }
}
