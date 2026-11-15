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
        Node<E> hashNext;

        Node(E item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object monitor = new Object();
    private Node<T> head;
    private Node<T> tail;
    private int size;

    private Node<T>[] buckets;
    private int resizeThreshold;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        buckets = (Node<T>[]) new Node[INITIAL_CAPACITY];
        resizeThreshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    public boolean add(T item) {
        synchronized (monitor) {
            requireNonNull(item);
            int hash = hashOf(item);
            if (find(item, hash) != null) {
                return false;
            }
            insertNew(item, hash, tail, null);
            return true;
        }
    }

    public boolean addFirst(T item) {
        synchronized (monitor) {
            requireNonNull(item);
            int hash = hashOf(item);
            if (find(item, hash) != null) {
                return false;
            }
            insertNew(item, hash, null, head);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        synchronized (monitor) {
            requireNonNull(item);
            checkPositionIndex(index);
            int hash = hashOf(item);
            if (find(item, hash) != null) {
                return false;
            }

            Node<T> next = index == size ? null : nodeAt(index);
            Node<T> previous = next == null ? tail : next.previous;
            insertNew(item, hash, previous, next);
            return true;
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        synchronized (monitor) {
            requireNonNull(item);
            Node<T> node = nodeAt(index);
            int hash = hashOf(item);
            if (findOtherThan(item, hash, node) != null) {
                throw new IllegalArgumentException("An equal item is already present");
            }

            T previousItem = node.item;
            if (node.hash != hash) {
                unlinkFromHash(node);
                node.item = item;
                node.hash = hash;
                linkIntoHash(node);
            } else {
                node.item = item;
            }
            return previousItem;
        }
    }

    public T removeAt(int index) {
        synchronized (monitor) {
            Node<T> node = nodeAt(index);
            T item = node.item;
            unlinkNode(node);
            return item;
        }
    }

    public boolean remove(T item) {
        synchronized (monitor) {
            requireNonNull(item);
            Node<T> node = find(item, hashOf(item));
            if (node == null) {
                return false;
            }
            unlinkNode(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (monitor) {
            if (head == null) {
                throw new IllegalStateException("List is empty");
            }
            T item = head.item;
            unlinkNode(head);
            return item;
        }
    }

    public T removeLast() {
        synchronized (monitor) {
            if (tail == null) {
                throw new IllegalStateException("List is empty");
            }
            T item = tail.item;
            unlinkNode(tail);
            return item;
        }
    }

    public boolean contains(T item) {
        synchronized (monitor) {
            requireNonNull(item);
            return find(item, hashOf(item)) != null;
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
            Node<T> node = head;
            for (int i = 0; i < size; i++) {
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

    private static int hashOf(Object item) {
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private int bucketIndex(int hash) {
        return hash & (buckets.length - 1);
    }

    private Node<T> find(T item, int hash) {
        for (Node<T> node = buckets[bucketIndex(hash)]; node != null; node = node.hashNext) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
        }
        return null;
    }

    private Node<T> findOtherThan(T item, int hash, Node<T> excluded) {
        for (Node<T> node = buckets[bucketIndex(hash)]; node != null; node = node.hashNext) {
            if (node != excluded && node.hash == hash && item.equals(node.item)) {
                return node;
            }
        }
        return null;
    }

    private void insertNew(T item, int hash, Node<T> previous, Node<T> next) {
        Node<T> node = new Node<T>(item, hash);
        ensureCapacityForOne();

        node.previous = previous;
        node.next = next;
        if (previous == null) {
            head = node;
        } else {
            previous.next = node;
        }
        if (next == null) {
            tail = node;
        } else {
            next.previous = node;
        }
        linkIntoHash(node);
        size++;
    }

    private void ensureCapacityForOne() {
        if (size < resizeThreshold || buckets.length >= MAX_CAPACITY) {
            return;
        }

        int newCapacity = buckets.length << 1;
        @SuppressWarnings("unchecked")
        Node<T>[] newBuckets = (Node<T>[]) new Node[newCapacity];

        for (int i = 0; i < buckets.length; i++) {
            Node<T> node = buckets[i];
            while (node != null) {
                Node<T> next = node.hashNext;
                int index = node.hash & (newCapacity - 1);
                node.hashNext = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        resizeThreshold = newCapacity - (newCapacity >>> 2);
    }

    private void linkIntoHash(Node<T> node) {
        int index = bucketIndex(node.hash);
        node.hashNext = buckets[index];
        buckets[index] = node;
    }

    private void unlinkFromHash(Node<T> node) {
        int index = bucketIndex(node.hash);
        Node<T> previous = null;
        Node<T> current = buckets[index];

        while (current != node) {
            previous = current;
            current = current.hashNext;
        }

        if (previous == null) {
            buckets[index] = current.hashNext;
        } else {
            previous.hashNext = current.hashNext;
        }
        node.hashNext = null;
    }

    private void unlinkNode(Node<T> node) {
        unlinkFromHash(node);

        if (node.previous == null) {
            head = node.next;
        } else {
            node.previous.next = node.next;
        }
        if (node.next == null) {
            tail = node.previous;
        } else {
            node.next.previous = node.previous;
        }

        size--;
        node.previous = null;
        node.next = null;
    }

    private Node<T> nodeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }

        if (index < (size >>> 1)) {
            Node<T> node = head;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }

        Node<T> node = tail;
        for (int i = size - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }
}
