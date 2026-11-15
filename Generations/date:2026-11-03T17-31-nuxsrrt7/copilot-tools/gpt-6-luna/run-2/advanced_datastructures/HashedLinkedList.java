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
    private Node<T>[] table;
    private Node<T> first;
    private Node<T> last;
    private int size;
    private int threshold;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        table = (Node<T>[]) new Node<?>[INITIAL_CAPACITY];
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    public boolean add(T item) {
        synchronized (monitor) {
            return insertAt(size, item);
        }
    }

    public boolean addFirst(T item) {
        synchronized (monitor) {
            return insertAt(0, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (monitor) {
            checkPositionIndex(index);
            return insertAt(index, item);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        synchronized (monitor) {
            Node<T> target = nodeAt(index);
            requireNonNull(item);

            int hash = spread(item.hashCode());
            Node<T> existing = findNode(hash, item);
            if (existing != null && existing != target) {
                throw new IllegalArgumentException("An equal item is already present");
            }

            T previous = target.item;
            if (existing == target) {
                target.item = item;
                return previous;
            }

            removeFromBucket(target);
            target.item = item;
            target.hash = hash;
            addToBucket(target);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (monitor) {
            Node<T> node = nodeAt(index);
            T item = node.item;
            unlink(node);
            return item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = spread(item.hashCode());
            Node<T> node = findNode(hash, item);
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
                throw new IllegalStateException("The list is empty");
            }
            T item = first.item;
            unlink(first);
            return item;
        }
    }

    public T removeLast() {
        synchronized (monitor) {
            if (last == null) {
                throw new IllegalStateException("The list is empty");
            }
            T item = last.item;
            unlink(last);
            return item;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = spread(item.hashCode());
            return findNode(hash, item) != null;
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
            for (int i = 0; i < size; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private boolean insertAt(int index, T item) {
        checkPositionIndex(index);
        requireNonNull(item);

        int hash = spread(item.hashCode());
        if (findNode(hash, item) != null) {
            return false;
        }

        Node<T> node = new Node<>(item, hash);
        ensureCapacityForOneMore();

        if (index == size) {
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
        } else {
            Node<T> successor = nodeAt(index);
            Node<T> predecessor = successor.previous;
            node.previous = predecessor;
            node.next = successor;
            successor.previous = node;
            if (predecessor == null) {
                first = node;
            } else {
                predecessor.next = node;
            }
        }

        addToBucket(node);
        size++;
        return true;
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private Node<T> nodeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
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

    private Node<T> findNode(int hash, T item) {
        for (Node<T> node = table[hash & (table.length - 1)];
             node != null;
             node = node.bucketNext) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
        }
        return null;
    }

    private void addToBucket(Node<T> node) {
        int bucket = node.hash & (table.length - 1);
        node.bucketNext = table[bucket];
        table[bucket] = node;
    }

    private void removeFromBucket(Node<T> target) {
        int bucket = target.hash & (table.length - 1);
        Node<T> previous = null;
        Node<T> node = table[bucket];

        while (node != null && node != target) {
            previous = node;
            node = node.bucketNext;
        }
        if (node == null) {
            throw new IllegalStateException("List and hash table are inconsistent");
        }

        if (previous == null) {
            table[bucket] = node.bucketNext;
        } else {
            previous.bucketNext = node.bucketNext;
        }
        node.bucketNext = null;
    }

    private void unlink(Node<T> node) {
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

        node.previous = null;
        node.next = null;
        size--;
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacityForOneMore() {
        if (size < threshold) {
            return;
        }
        if (table.length >= MAX_CAPACITY) {
            throw new IllegalStateException("Maximum hash table capacity reached");
        }

        int newCapacity = table.length << 1;
        Node<T>[] newTable = (Node<T>[]) new Node<?>[newCapacity];

        for (Node<T> node = first; node != null; node = node.next) {
            int bucket = node.hash & (newCapacity - 1);
            node.bucketNext = newTable[bucket];
            newTable[bucket] = node;
        }

        table = newTable;
        threshold = newCapacity - (newCapacity >>> 2);
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
