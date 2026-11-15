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

    private final Object lock = new Object();
    private Node<T>[] table;
    private Node<T> first;
    private Node<T> last;
    private int size;
    private int threshold;

    public HashedLinkedList() {
        table = newTable(INITIAL_CAPACITY);
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    public boolean add(T item) {
        synchronized (lock) {
            return insertBefore(null, item);
        }
    }

    public boolean addFirst(T item) {
        synchronized (lock) {
            return insertBefore(first, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return insertBefore(index == size ? null : nodeAt(index), item);
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
            checkElementIndex(index);
            int hash = itemHash(item);
            Node<T> node = nodeAt(index);
            Node<T> existing = find(item, hash);

            if (existing != null && existing != node) {
                throw new IllegalArgumentException(
                        "An equal item exists at another position");
            }

            T previous = node.item;
            removeHashEntry(node);
            node.item = item;
            node.hash = hash;
            addHashEntry(node);
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
            int hash = itemHash(item);
            Node<T> node = find(item, hash);
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        }
    }

    // Endpoint removal never waits for an item.
    public T removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlink(first);
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlink(last);
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            return find(item, itemHash(item)) != null;
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
            int index = 0;
            for (Node<T> node = first; node != null; node = node.next) {
                result[index++] = node.item;
            }
            return result;
        }
    }

    // All instance helpers are called with lock held.
    private boolean insertBefore(Node<T> successor, T item) {
        int hash = itemHash(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Maximum size reached");
        }

        Node<T> node = new Node<T>(item, hash);
        if (size >= threshold) {
            grow();
        }

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
        node.previous = null;
        node.next = null;
        return node.item;
    }

    private Node<T> find(T item, int hash) {
        int bucket = hash & (table.length - 1);
        for (Node<T> node = table[bucket];
                node != null; node = node.bucketNext) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
        }
        return null;
    }

    private void addHashEntry(Node<T> node) {
        int bucket = node.hash & (table.length - 1);
        node.bucketNext = table[bucket];
        table[bucket] = node;
    }

    private void removeHashEntry(Node<T> node) {
        int bucket = node.hash & (table.length - 1);
        Node<T> previous = null;
        Node<T> current = table[bucket];

        while (current != node) {
            if (current == null) {
                throw new IllegalStateException("Hash index invariant violated");
            }
            previous = current;
            current = current.bucketNext;
        }

        if (previous == null) {
            table[bucket] = node.bucketNext;
        } else {
            previous.bucketNext = node.bucketNext;
        }
        node.bucketNext = null;
    }

    private void grow() {
        int oldCapacity = table.length;
        if (oldCapacity == MAX_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }

        int newCapacity = oldCapacity << 1;
        Node<T>[] expanded = newTable(newCapacity);

        // Only bucket links change; retained list nodes keep their identity.
        for (int bucket = 0; bucket < oldCapacity; bucket++) {
            Node<T> node = table[bucket];
            while (node != null) {
                Node<T> next = node.bucketNext;
                int newBucket = node.hash & (newCapacity - 1);
                node.bucketNext = expanded[newBucket];
                expanded[newBucket] = node;
                node = next;
            }
        }

        table = expanded;
        threshold = newCapacity - (newCapacity >>> 2);
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

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private static int itemHash(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    @SuppressWarnings("unchecked")
    private static <E> Node<E>[] newTable(int capacity) {
        return (Node<E>[]) new Node<?>[capacity];
    }
}
