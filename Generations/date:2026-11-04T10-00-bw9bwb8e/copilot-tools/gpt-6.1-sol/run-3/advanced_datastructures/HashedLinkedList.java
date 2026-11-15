/**
 * A duplicate-free, ordered list with a custom hash index.
 *
 * All public operations synchronize on one private monitor.
 * Empty endpoint removals throw immediately; no operation waits for items.
 * Retained items must have stable equals and hashCode implementations.
 */
@HashedLinkedList.ThreadSafe
public final class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private static final class Node<E> {
        E item;
        int hash;
        Node<E> previous;
        Node<E> next;
        Node<E> bucketNext;

        Node(E item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object monitor = new Object();
    private Node<T>[] buckets;
    private Node<T> first;
    private Node<T> last;
    private int size;
    private int threshold;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        buckets = (Node<T>[]) new Node<?>[INITIAL_CAPACITY];
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    public boolean add(T item) {
        synchronized (monitor) {
            requireItem(item);
            return insertBefore(item, null);
        }
    }

    public boolean addFirst(T item) {
        synchronized (monitor) {
            requireItem(item);
            return insertBefore(item, first);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (monitor) {
            requireItem(item);
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return insertBefore(item, index == size ? null : nodeAt(index));
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        synchronized (monitor) {
            requireItem(item);
            checkElementIndex(index);

            Node<T> node = nodeAt(index);
            int hash = hash(item);
            Node<T> existing = find(item, hash);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException(
                        "An equal item exists at another position");
            }

            T previous = node.item;
            if (node.hash != hash) {
                removeHashEntry(node);
                node.hash = hash;
                addHashEntry(node);
            }
            node.item = item;
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return unlink(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        synchronized (monitor) {
            requireItem(item);
            Node<T> node = find(item, hash(item));
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (monitor) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlink(first);
        }
    }

    public T removeLast() {
        synchronized (monitor) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlink(last);
        }
    }

    public boolean contains(T item) {
        synchronized (monitor) {
            requireItem(item);
            return find(item, hash(item)) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[size];
            int index = 0;
            for (Node<T> node = first; node != null; node = node.next) {
                result[index++] = node.item;
            }
            return result;
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int hash(Object item) {
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
    }

    private Node<T> nodeAt(int index) {
        if (index < (size >>> 1)) {
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
        int bucket = hash & (buckets.length - 1);
        for (Node<T> node = buckets[bucket];
                node != null; node = node.bucketNext) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
        }
        return null;
    }

    private boolean insertBefore(T item, Node<T> successor) {
        int hash = hash(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Maximum size reached");
        }

        Node<T> node = new Node<T>(item, hash);
        ensureCapacity(size + 1);

        Node<T> predecessor =
                successor == null ? last : successor.previous;

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

        addHashEntry(node);
        size++;
        return true;
    }

    private void addHashEntry(Node<T> node) {
        int bucket = node.hash & (buckets.length - 1);
        node.bucketNext = buckets[bucket];
        buckets[bucket] = node;
    }

    private void removeHashEntry(Node<T> node) {
        int bucket = node.hash & (buckets.length - 1);
        Node<T> previous = null;
        Node<T> current = buckets[bucket];

        while (current != node) {
            if (current == null) {
                throw new IllegalStateException("Hash index is inconsistent");
            }
            previous = current;
            current = current.bucketNext;
        }

        if (previous == null) {
            buckets[bucket] = node.bucketNext;
        } else {
            previous.bucketNext = node.bucketNext;
        }
        node.bucketNext = null;
    }

    private T unlink(Node<T> node) {
        removeHashEntry(node);

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

        size--;
        T item = node.item;
        node.item = null;
        node.previous = null;
        node.next = null;
        return item;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int requiredSize) {
        if (requiredSize <= threshold) {
            return;
        }
        if (buckets.length == MAX_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }

        int capacity = buckets.length << 1;
        Node<T>[] replacement = (Node<T>[]) new Node<?>[capacity];

        // Rehash existing nodes without replacing them or invoking item code.
        for (Node<T> node = first; node != null; node = node.next) {
            int bucket = node.hash & (capacity - 1);
            node.bucketNext = replacement[bucket];
            replacement[bucket] = node;
        }

        buckets = replacement;
        threshold = capacity - (capacity >>> 2);
    }
}
