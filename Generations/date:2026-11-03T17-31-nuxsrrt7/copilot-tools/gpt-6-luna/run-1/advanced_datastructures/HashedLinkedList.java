@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;

    private static final class Node<E> {
        E item;
        Node<E> previous;
        Node<E> next;
        Entry<E> entry;

        Node(E item) {
            this.item = item;
        }
    }

    private static final class Entry<E> {
        E item;
        Node<E> node;
        int hash;
        Entry<E> bucketPrevious;
        Entry<E> bucketNext;

        Entry(E item, Node<E> node, int hash) {
            this.item = item;
            this.node = node;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();
    private Entry<T>[] buckets;
    private Node<T> first;
    private Node<T> last;
    private int size;

    public HashedLinkedList() {
        buckets = newTable(INITIAL_CAPACITY);
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int hash = item.hashCode();
            if (findEntry(item, hash) != null) {
                return false;
            }
            insertNew(size, item, hash);
            return true;
        }
    }

    public boolean addFirst(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int hash = item.hashCode();
            if (findEntry(item, hash) != null) {
                return false;
            }
            insertNew(0, item, hash);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkInsertIndex(index);
            int hash = item.hashCode();
            if (findEntry(item, hash) != null) {
                return false;
            }
            insertNew(index, item, hash);
            return true;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return nodeAtChecked(index).item;
        }
    }

    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<T> node = nodeAtChecked(index);
            int hash = item.hashCode();
            Entry<T> existing = findEntry(item, hash);
            if (existing != null && existing.node != node) {
                throw new IllegalArgumentException("An equal item already exists");
            }

            T previous = node.item;
            node.item = item;
            node.entry.item = item;
            node.entry.hash = hash;
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            return unlinkNode(nodeAtChecked(index));
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int hash = item.hashCode();
            Entry<T> entry = findEntry(item, hash);
            if (entry == null) {
                return false;
            }
            unlinkNode(entry.node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("The list is empty");
            }
            return unlinkNode(first);
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("The list is empty");
            }
            return unlinkNode(last);
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return findEntry(item, item.hashCode()) != null;
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

    private void insertNew(int index, T item, int hash) {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List capacity exceeded");
        }

        Node<T> node = new Node<T>(item);
        Entry<T> entry = new Entry<T>(item, node, hash);
        node.entry = entry;
        ensureCapacityForOneMore();

        Node<T> successor = index == size ? null : nodeAt(index);
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

        linkEntry(entry);
        size++;
    }

    private T unlinkNode(Node<T> node) {
        T item = node.item;
        Entry<T> entry = node.entry;

        if (entry.bucketPrevious == null) {
            buckets[bucketIndex(entry.hash, buckets.length)] = entry.bucketNext;
        } else {
            entry.bucketPrevious.bucketNext = entry.bucketNext;
        }
        if (entry.bucketNext != null) {
            entry.bucketNext.bucketPrevious = entry.bucketPrevious;
        }

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
        node.item = null;
        node.previous = null;
        node.next = null;
        node.entry = null;
        entry.item = null;
        entry.node = null;
        entry.bucketPrevious = null;
        entry.bucketNext = null;
        return item;
    }

    private Node<T> nodeAtChecked(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return nodeAt(index);
    }

    private void checkInsertIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
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

    private Entry<T> findEntry(T item, int hash) {
        int index = bucketIndex(hash, buckets.length);
        for (Entry<T> entry = buckets[index]; entry != null; entry = entry.bucketNext) {
            if (entry.hash == hash && entry.item.equals(item)) {
                return entry;
            }
        }
        return null;
    }

    private void linkEntry(Entry<T> entry) {
        int index = bucketIndex(entry.hash, buckets.length);
        Entry<T> oldFirst = buckets[index];
        entry.bucketNext = oldFirst;
        if (oldFirst != null) {
            oldFirst.bucketPrevious = entry;
        }
        buckets[index] = entry;
    }

    private void ensureCapacityForOneMore() {
        if (size < buckets.length - (buckets.length >>> 2)) {
            return;
        }
        if (buckets.length >= (1 << 30)) {
            throw new IllegalStateException("Hash table capacity exceeded");
        }

        Entry<T>[] oldBuckets = buckets;
        Entry<T>[] newBuckets = newTable(oldBuckets.length << 1);
        for (Entry<T> bucket : oldBuckets) {
            Entry<T> entry = bucket;
            while (entry != null) {
                Entry<T> next = entry.bucketNext;
                int index = bucketIndex(entry.hash, newBuckets.length);
                entry.bucketPrevious = null;
                entry.bucketNext = newBuckets[index];
                if (entry.bucketNext != null) {
                    entry.bucketNext.bucketPrevious = entry;
                }
                newBuckets[index] = entry;
                entry = next;
            }
        }
        buckets = newBuckets;
    }

    private static int bucketIndex(int hash, int capacity) {
        int spreadHash = hash ^ (hash >>> 16);
        return (spreadHash & 0x7fffffff) % capacity;
    }

    @SuppressWarnings("unchecked")
    private Entry<T>[] newTable(int capacity) {
        return (Entry<T>[]) new Entry<?>[capacity];
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }
}
