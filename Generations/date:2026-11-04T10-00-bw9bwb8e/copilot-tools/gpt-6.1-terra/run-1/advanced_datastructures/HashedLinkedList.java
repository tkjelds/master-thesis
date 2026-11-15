@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
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

    private final Object lock = new Object();
    private Node<T>[] buckets = newTable(INITIAL_CAPACITY);
    private Node<T> first;
    private Node<T> last;
    private int size;

    @SuppressWarnings("unchecked")
    private static <E> Node<E>[] newTable(int capacity) {
        return (Node<E>[]) new Node<?>[capacity];
    }

    private static int hash(Object item) {
        int value = item.hashCode();
        return value ^ (value >>> 16);
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
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

    private Node<T> find(T item, int hash) {
        Node<T> node = buckets[hash & (buckets.length - 1)];
        while (node != null) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
            node = node.bucketNext;
        }
        return null;
    }

    private Node<T> nodeAt(int index) {
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

    private void growIfNeeded() {
        int capacity = buckets.length;
        if (capacity == MAX_CAPACITY || size < capacity - (capacity >>> 2)) {
            return;
        }

        Node<T>[] newBuckets = newTable(capacity << 1);
        for (int i = 0; i < capacity; i++) {
            Node<T> node = buckets[i];
            while (node != null) {
                Node<T> next = node.bucketNext;
                int bucket = node.hash & (newBuckets.length - 1);
                node.bucketNext = newBuckets[bucket];
                newBuckets[bucket] = node;
                node = next;
            }
        }
        buckets = newBuckets;
    }

    private void addToIndex(Node<T> node) {
        int bucket = node.hash & (buckets.length - 1);
        node.bucketNext = buckets[bucket];
        buckets[bucket] = node;
    }

    private void removeFromIndex(Node<T> node) {
        int bucket = node.hash & (buckets.length - 1);
        Node<T> current = buckets[bucket];
        Node<T> previous = null;

        while (current != node) {
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

    private boolean insertAt(int index, T item) {
        requireItem(item);
        checkPositionIndex(index);

        int itemHash = hash(item);
        if (find(item, itemHash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("list is too large");
        }

        Node<T> successor = index == size ? null : nodeAt(index);
        Node<T> node = new Node<T>(item, itemHash);
        growIfNeeded();

        Node<T> predecessor = successor == null ? last : successor.previous;
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

        addToIndex(node);
        size++;
        return true;
    }

    private T unlink(Node<T> node) {
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

        size--;
        node.previous = null;
        node.next = null;
        return node.item;
    }

    public boolean add(T item) {
        synchronized (lock) {
            return insertAt(size, item);
        }
    }

    public boolean addFirst(T item) {
        synchronized (lock) {
            return insertAt(0, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            return insertAt(index, item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkElementIndex(index);

            Node<T> node = nodeAt(index);
            int itemHash = hash(item);
            Node<T> existing = find(item, itemHash);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("item already exists at another position");
            }

            T previous = node.item;
            if (existing == node) {
                node.item = item;
            } else {
                removeFromIndex(node);
                node.item = item;
                node.hash = itemHash;
                addToIndex(node);
            }
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return unlink(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
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
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("list is empty");
            }
            return unlink(first);
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("list is empty");
            }
            return unlink(last);
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireItem(item);
            return find(item, hash(item)) != null;
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
            for (int i = 0; i < size; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }
}
