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

    private final Object monitor = new Object();
    private Node<T>[] buckets = newTable(INITIAL_CAPACITY);
    private int threshold = INITIAL_CAPACITY - INITIAL_CAPACITY / 4;
    private Node<T> first;
    private Node<T> last;
    private int size;

    @SuppressWarnings("unchecked")
    private static <E> Node<E>[] newTable(int capacity) {
        return (Node<E>[]) new Node<?>[capacity];
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int hash(Object item) {
        int h = item.hashCode();
        return h ^ (h >>> 16);
    }

    private Node<T> findNode(T item, int hash) {
        int bucket = hash & (buckets.length - 1);
        for (Node<T> node = buckets[bucket]; node != null; node = node.bucketNext) {
            if (node.hash == hash && node.item.equals(item)) {
                return node;
            }
        }
        return null;
    }

    private void growIfNeeded() {
        int capacity = buckets.length;
        if (size < threshold || capacity == MAX_CAPACITY) {
            return;
        }

        int newCapacity = capacity << 1;
        Node<T>[] newBuckets = newTable(newCapacity);
        for (int i = 0; i < capacity; i++) {
            Node<T> node = buckets[i];
            while (node != null) {
                Node<T> next = node.bucketNext;
                int bucket = node.hash & (newCapacity - 1);
                node.bucketNext = newBuckets[bucket];
                newBuckets[bucket] = node;
                node = next;
            }
        }
        buckets = newBuckets;
        threshold = newCapacity - newCapacity / 4;
    }

    private void addToBucket(Node<T> node) {
        int bucket = node.hash & (buckets.length - 1);
        node.bucketNext = buckets[bucket];
        buckets[bucket] = node;
    }

    private void removeFromBucket(Node<T> node) {
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

    private boolean insertBefore(Node<T> successor, T item) {
        int itemHash = hash(item);
        if (findNode(item, itemHash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("list is too large");
        }

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

        addToBucket(node);
        size++;
        return true;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index);
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

    private T unlink(Node<T> node) {
        removeFromBucket(node);

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

    public boolean add(T item) {
        requireItem(item);
        synchronized (monitor) {
            return insertBefore(null, item);
        }
    }

    public boolean addFirst(T item) {
        requireItem(item);
        synchronized (monitor) {
            return insertBefore(first, item);
        }
    }

    public boolean insert(int index, T item) {
        requireItem(item);
        synchronized (monitor) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            Node<T> successor = index == size ? null : nodeAt(index);
            return insertBefore(successor, item);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        requireItem(item);
        synchronized (monitor) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            int itemHash = hash(item);
            Node<T> existing = findNode(item, itemHash);

            if (existing != null && existing != node) {
                throw new IllegalArgumentException("duplicate item");
            }

            T previous = node.item;
            if (existing == node) {
                node.item = item;
            } else {
                removeFromBucket(node);
                node.item = item;
                node.hash = itemHash;
                addToBucket(node);
            }
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
        requireItem(item);
        synchronized (monitor) {
            Node<T> node = findNode(item, hash(item));
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
                throw new IllegalStateException("list is empty");
            }
            return unlink(first);
        }
    }

    public T removeLast() {
        synchronized (monitor) {
            if (last == null) {
                throw new IllegalStateException("list is empty");
            }
            return unlink(last);
        }
    }

    public boolean contains(T item) {
        requireItem(item);
        synchronized (monitor) {
            return findNode(item, hash(item)) != null;
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
            Node<T> node = first;
            for (int i = 0; i < result.length; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }
}
