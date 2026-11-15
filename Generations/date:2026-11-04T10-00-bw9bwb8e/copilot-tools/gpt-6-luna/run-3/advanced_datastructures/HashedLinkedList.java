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

        Node(E item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private static final class HashEntry<E> {
        Node<E> node;
        HashEntry<E> next;

        HashEntry(Node<E> node) {
            this.node = node;
        }
    }

    private final Object mutex = new Object();
    private HashEntry<T>[] buckets;
    private Node<T> first;
    private Node<T> last;
    private int size;

    public HashedLinkedList() {
        buckets = newBucketArray(INITIAL_CAPACITY);
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            int hash = spread(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            addBefore(item, hash, null);
            return true;
        }
    }

    public boolean addFirst(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            int hash = spread(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            addBefore(item, hash, first);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (mutex) {
            checkPositionIndex(index);
            int hash = spread(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            addBefore(item, hash, index == size ? null : nodeAt(index));
            return true;
        }
    }

    public T get(int index) {
        synchronized (mutex) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (mutex) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            int hash = spread(item.hashCode());
            HashEntry<T> existing = findEntry(item, hash);
            if (existing != null && existing.node != node) {
                throw new IllegalArgumentException("Item already exists in the list");
            }

            T previous = node.item;
            node.item = item;
            node.hash = hash;
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (mutex) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            T item = node.item;
            removeNode(node);
            return item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            int hash = spread(item.hashCode());
            HashEntry<T> entry = findEntry(item, hash);
            if (entry == null) {
                return false;
            }
            removeNode(entry.node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (mutex) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            T item = first.item;
            removeNode(first);
            return item;
        }
    }

    public T removeLast() {
        synchronized (mutex) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            T item = last.item;
            removeNode(last);
            return item;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            int hash = spread(item.hashCode());
            return findEntry(item, hash) != null;
        }
    }

    public int size() {
        synchronized (mutex) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (mutex) {
            Object[] result = new Object[size];
            Node<T> node = first;
            for (int i = 0; i < size; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private void addBefore(T item, int hash, Node<T> successor) {
        Node<T> node = new Node<T>(item, hash);
        HashEntry<T> entry = new HashEntry<T>(node);
        ensureCapacity();

        int bucket = hash & (buckets.length - 1);
        entry.next = buckets[bucket];
        buckets[bucket] = entry;

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
        size++;
    }

    private void removeNode(Node<T> node) {
        int bucket = node.hash & (buckets.length - 1);
        HashEntry<T> previousEntry = null;
        HashEntry<T> entry = buckets[bucket];
        while (entry != null && entry.node != node) {
            previousEntry = entry;
            entry = entry.next;
        }
        if (entry == null) {
            throw new IllegalStateException("List node is missing from the hash table");
        }

        if (previousEntry == null) {
            buckets[bucket] = entry.next;
        } else {
            previousEntry.next = entry.next;
        }
        entry.next = null;

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

    private HashEntry<T> findEntry(T item, int hash) {
        int bucket = hash & (buckets.length - 1);
        HashEntry<T> entry = buckets[bucket];
        while (entry != null) {
            Node<T> node = entry.node;
            if (node.hash == hash && node.item.equals(item)) {
                return entry;
            }
            entry = entry.next;
        }
        return null;
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

    private void ensureCapacity() {
        int capacity = buckets.length;
        int threshold = capacity - (capacity >>> 2);
        if (size < threshold || capacity == MAX_CAPACITY) {
            return;
        }

        HashEntry<T>[] newBuckets = newBucketArray(capacity << 1);
        for (HashEntry<T> head : buckets) {
            HashEntry<T> entry = head;
            while (entry != null) {
                HashEntry<T> next = entry.next;
                int bucket = entry.node.hash & (newBuckets.length - 1);
                entry.next = newBuckets[bucket];
                newBuckets[bucket] = entry;
                entry = next;
            }
        }
        buckets = newBuckets;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
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

    @SuppressWarnings("unchecked")
    private HashEntry<T>[] newBucketArray(int capacity) {
        return (HashEntry<T>[]) new HashEntry<?>[capacity];
    }
}
