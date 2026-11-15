@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final HashIndex<T> hashIndex = new HashIndex<T>();
    private Node<T> first;
    private Node<T> last;
    private int size;

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return insertAtLocked(size, item, item.hashCode());
        }
    }

    public boolean addFirst(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return insertAtLocked(0, item, item.hashCode());
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (lock) {
            checkPositionIndex(index);
            return insertAtLocked(index, item, item.hashCode());
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
            Node<T> matching = hashIndex.find(item, hash);
            if (matching != null && matching != node) {
                throw new IllegalArgumentException("An equal item is already present");
            }

            T previous = node.item;
            Entry<T> replacement = new Entry<T>(item, node, hash);
            hashIndex.removeNode(node);
            node.item = item;
            hashIndex.addEntry(replacement);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            Node<T> node = nodeAtChecked(index);
            T item = node.item;
            unlinkNode(node);
            return item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<T> node = hashIndex.find(item, item.hashCode());
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
                throw new IllegalStateException("List is empty");
            }
            T item = first.item;
            unlinkNode(first);
            return item;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            T item = last.item;
            unlinkNode(last);
            return item;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return hashIndex.find(item, item.hashCode()) != null;
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
            Node<T> current = first;
            for (int i = 0; i < result.length; i++) {
                result[i] = current.item;
                current = current.next;
            }
            return result;
        }
    }

    private boolean insertAtLocked(int index, T item, int hash) {
        checkPositionIndex(index);
        if (hashIndex.find(item, hash) != null) {
            return false;
        }

        Node<T> node = new Node<T>(item);
        hashIndex.add(item, node, hash);

        if (index == 0) {
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
        } else if (index == size) {
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
            predecessor.next = node;
            successor.previous = node;
        }

        size++;
        return true;
    }

    private void unlinkNode(Node<T> node) {
        hashIndex.removeNode(node);

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

    private Node<T> nodeAtChecked(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
        return nodeAt(index);
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private Node<T> nodeAt(int index) {
        if (index < (size >>> 1)) {
            Node<T> current = first;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current;
        }

        Node<T> current = last;
        for (int i = size - 1; i > index; i--) {
            current = current.previous;
        }
        return current;
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static final class Node<E> {
        private E item;
        private Node<E> previous;
        private Node<E> next;

        private Node(E item) {
            this.item = item;
        }
    }

    private static final class Entry<E> {
        private final Node<E> node;
        private final int hash;
        private E item;
        private Entry<E> next;

        private Entry(E item, Node<E> node, int hash) {
            this.item = item;
            this.node = node;
            this.hash = hash;
        }
    }

    private static final class HashIndex<E> {
        private Entry<E>[] buckets;
        private int size;

        @SuppressWarnings("unchecked")
        private HashIndex() {
            buckets = (Entry<E>[]) new Entry[16];
        }

        private Node<E> find(E item, int hash) {
            int index = bucketIndex(hash, buckets.length);
            for (Entry<E> entry = buckets[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && item.equals(entry.item)) {
                    return entry.node;
                }
            }
            return null;
        }

        private void add(E item, Node<E> node, int hash) {
            Entry<E> entry = new Entry<E>(item, node, hash);
            ensureCapacity(size + 1);
            addEntry(entry);
        }

        private void addEntry(Entry<E> entry) {
            int index = bucketIndex(entry.hash, buckets.length);
            entry.next = buckets[index];
            buckets[index] = entry;
            size++;
        }

        private void removeNode(Node<E> node) {
            int index = bucketIndexForNode(node);
            Entry<E> previous = null;
            Entry<E> current = buckets[index];

            while (current != null) {
                if (current.node == node) {
                    if (previous == null) {
                        buckets[index] = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    current.next = null;
                    size--;
                    return;
                }
                previous = current;
                current = current.next;
            }
        }

        private int bucketIndexForNode(Node<E> node) {
            for (int i = 0; i < buckets.length; i++) {
                for (Entry<E> entry = buckets[i]; entry != null; entry = entry.next) {
                    if (entry.node == node) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private void ensureCapacity(int required) {
            if (buckets.length == (1 << 30)) {
                return;
            }
            if (required <= buckets.length - (buckets.length >>> 2)) {
                return;
            }
            resize(buckets.length << 1);
        }

        @SuppressWarnings("unchecked")
        private void resize(int capacity) {
            Entry<E>[] replacement = (Entry<E>[]) new Entry[capacity];
            for (int i = 0; i < buckets.length; i++) {
                Entry<E> entry = buckets[i];
                while (entry != null) {
                    Entry<E> next = entry.next;
                    int index = bucketIndex(entry.hash, capacity);
                    entry.next = replacement[index];
                    replacement[index] = entry;
                    entry = next;
                }
            }
            buckets = replacement;
        }

        private static int bucketIndex(int hash, int capacity) {
            int spread = hash ^ (hash >>> 16);
            return spread & (capacity - 1);
        }
    }
}
