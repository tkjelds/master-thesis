@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object monitor = new Object();
    private Node<T> first;
    private Node<T> last;
    private int size;
    private Entry<T>[] buckets;
    private int threshold;

    public HashedLinkedList() {
        buckets = newEntryArray(INITIAL_CAPACITY);
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

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
        final Node<E> node;
        final int hash;
        Entry<E> next;

        Entry(Node<E> node, int hash) {
            this.node = node;
            this.hash = hash;
        }
    }

    @SuppressWarnings("unchecked")
    private Entry<T>[] newEntryArray(int capacity) {
        return (Entry<T>[]) new Entry<?>[capacity];
    }

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int bucketIndex(int hash, int capacity) {
        return hash & (capacity - 1);
    }

    private Entry<T> findEntry(T item, int hash) {
        Entry<T> entry = buckets[bucketIndex(hash, buckets.length)];
        while (entry != null) {
            if (entry.hash == hash && entry.node.item.equals(item)) {
                return entry;
            }
            entry = entry.next;
        }
        return null;
    }

    private void growIfNeeded() {
        if (size < threshold || buckets.length >= MAX_CAPACITY) {
            return;
        }

        int newCapacity = buckets.length << 1;
        Entry<T>[] newBuckets = newEntryArray(newCapacity);

        for (Entry<T> head : buckets) {
            Entry<T> entry = head;
            while (entry != null) {
                Entry<T> next = entry.next;
                int index = bucketIndex(entry.hash, newCapacity);
                entry.next = newBuckets[index];
                newBuckets[index] = entry;
                entry = next;
            }
        }

        buckets = newBuckets;
        threshold = newCapacity - (newCapacity >>> 2);
    }

    private void addNodeBefore(Node<T> successor, T item, int hash) {
        growIfNeeded();

        Node<T> node = new Node<T>(item);
        Entry<T> entry = new Entry<T>(node, hash);
        node.entry = entry;

        int index = bucketIndex(hash, buckets.length);
        entry.next = buckets[index];
        buckets[index] = entry;

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

        size++;
    }

    private Node<T> nodeAt(int index) {
        if (index < (size >>> 1)) {
            Node<T> node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        } else {
            Node<T> node = last;
            for (int i = size - 1; i > index; i--) {
                node = node.previous;
            }
            return node;
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

    private void removeEntry(Entry<T> target) {
        int index = bucketIndex(target.hash, buckets.length);
        Entry<T> previous = null;
        Entry<T> entry = buckets[index];

        while (entry != target) {
            previous = entry;
            entry = entry.next;
        }

        if (previous == null) {
            buckets[index] = target.next;
        } else {
            previous.next = target.next;
        }
        target.next = null;
    }

    private T unlinkNode(Node<T> node) {
        removeEntry(node.entry);

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
        node.previous = null;
        node.next = null;
        node.entry = null;
        return item;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            int hash = spreadHash(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            addNodeBefore(null, item, hash);
            return true;
        }
    }

    public boolean addFirst(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            int hash = spreadHash(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            addNodeBefore(first, item, hash);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            checkPositionIndex(index);
            int hash = spreadHash(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            Node<T> successor = index == size ? null : nodeAt(index);
            addNodeBefore(successor, item, hash);
            return true;
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            int hash = spreadHash(item.hashCode());
            Entry<T> existing = findEntry(item, hash);
            if (existing != null && existing.node != node) {
                throw new IllegalArgumentException("An equal item is already present");
            }

            T previous = node.item;
            node.item = item;
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return unlinkNode(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            int hash = spreadHash(item.hashCode());
            Entry<T> entry = findEntry(item, hash);
            if (entry == null) {
                return false;
            }
            unlinkNode(entry.node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (monitor) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlinkNode(first);
        }
    }

    public T removeLast() {
        synchronized (monitor) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlinkNode(last);
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            int hash = spreadHash(item.hashCode());
            return findEntry(item, hash) != null;
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
