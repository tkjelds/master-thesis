@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    private Node<T> first;
    private Node<T> last;
    private int size;

    private HashEntry<T>[] table;
    private int threshold;

    public HashedLinkedList() {
        table = newTable(16);
        threshold = 12;
    }

    public boolean add(T item) {
        synchronized (lock) {
            requireNonNull(item);
            int hash = item.hashCode();
            if (findEntry(item, hash) != null) {
                return false;
            }

            Node<T> node = new Node<T>(item, hash);
            HashEntry<T> entry = new HashEntry<T>(item, node, hash);
            ensureCapacityForOneMore();
            linkAtEnd(node);
            addEntry(entry);
            size++;
            return true;
        }
    }

    public boolean addFirst(T item) {
        synchronized (lock) {
            requireNonNull(item);
            int hash = item.hashCode();
            if (findEntry(item, hash) != null) {
                return false;
            }

            Node<T> node = new Node<T>(item, hash);
            HashEntry<T> entry = new HashEntry<T>(item, node, hash);
            ensureCapacityForOneMore();
            linkAtFront(node);
            addEntry(entry);
            size++;
            return true;
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            requireNonNull(item);
            checkPositionIndex(index);

            int hash = item.hashCode();
            if (findEntry(item, hash) != null) {
                return false;
            }

            Node<T> node = new Node<T>(item, hash);
            HashEntry<T> entry = new HashEntry<T>(item, node, hash);
            ensureCapacityForOneMore();

            if (index == size) {
                linkAtEnd(node);
            } else {
                linkBefore(nodeAt(index));
            }
            addEntry(entry);
            size++;
            return true;
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
            requireNonNull(item);
            checkElementIndex(index);

            Node<T> node = nodeAt(index);
            int hash = item.hashCode();
            HashEntry<T> duplicate = findEntry(item, hash);
            if (duplicate != null && duplicate.node != node) {
                throw new IllegalArgumentException("Item is already present");
            }

            HashEntry<T> entry = duplicate != null ? duplicate : findEntryForNode(node);
            T previous = node.item;
            unlinkEntry(entry);
            node.item = item;
            node.hash = hash;
            entry.key = item;
            entry.hash = hash;
            addEntry(entry);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            T removed = node.item;
            removeNode(node);
            return removed;
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireNonNull(item);
            int hash = item.hashCode();
            HashEntry<T> entry = findEntry(item, hash);
            if (entry == null) {
                return false;
            }

            removeNode(entry.node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }

            T removed = first.item;
            removeNode(first);
            return removed;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }

            T removed = last.item;
            removeNode(last);
            return removed;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireNonNull(item);
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
            Node<T> current = first;
            for (int i = 0; i < size; i++) {
                result[i] = current.item;
                current = current.next;
            }
            return result;
        }
    }

    private void requireNonNull(T item) {
        if (item == null) {
            throw new NullPointerException("Null items are not allowed");
        }
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

    private void linkAtFront(Node<T> node) {
        node.next = first;
        if (first == null) {
            last = node;
        } else {
            first.previous = node;
        }
        first = node;
    }

    private void linkAtEnd(Node<T> node) {
        node.previous = last;
        if (last == null) {
            first = node;
        } else {
            last.next = node;
        }
        last = node;
    }

    private void linkBefore(Node<T> node) {
        Node<T> next = node.next;
        node.next = next;
        node.previous = null;
        if (next == null) {
            last = node;
        } else {
            next.previous = node;
        }
        first = node;
    }

    private void linkBefore(Node<T> newNode, Node<T> next) {
        newNode.next = next;
        newNode.previous = next.previous;
        if (next.previous == null) {
            first = newNode;
        } else {
            next.previous.next = newNode;
        }
        next.previous = newNode;
    }

    private void removeNode(Node<T> node) {
        HashEntry<T> entry = findEntryForNode(node);
        unlinkEntry(entry);

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
        int index = bucketIndex(hash, table.length);
        for (HashEntry<T> entry = table[index]; entry != null; entry = entry.next) {
            if (entry.hash == hash && entry.key.equals(item)) {
                return entry;
            }
        }
        return null;
    }

    private HashEntry<T> findEntryForNode(Node<T> node) {
        int index = bucketIndex(node.hash, table.length);
        for (HashEntry<T> entry = table[index]; entry != null; entry = entry.next) {
            if (entry.node == node) {
                return entry;
            }
        }
        throw new IllegalStateException("List node has no hash entry");
    }

    private void addEntry(HashEntry<T> entry) {
        int index = bucketIndex(entry.hash, table.length);
        entry.next = table[index];
        table[index] = entry;
    }

    private void unlinkEntry(HashEntry<T> target) {
        int index = bucketIndex(target.hash, table.length);
        HashEntry<T> previous = null;
        HashEntry<T> current = table[index];

        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    table[index] = current.next;
                } else {
                    previous.next = current.next;
                }
                current.next = null;
                return;
            }
            previous = current;
            current = current.next;
        }

        throw new IllegalStateException("Hash entry is missing");
    }

    private void ensureCapacityForOneMore() {
        if (size + 1 <= threshold || table.length >= (1 << 30)) {
            return;
        }

        HashEntry<T>[] oldTable = table;
        HashEntry<T>[] newTable = newTable(oldTable.length << 1);
        for (int i = 0; i < oldTable.length; i++) {
            HashEntry<T> entry = oldTable[i];
            while (entry != null) {
                HashEntry<T> next = entry.next;
                int index = bucketIndex(entry.hash, newTable.length);
                entry.next = newTable[index];
                newTable[index] = entry;
                entry = next;
            }
        }

        table = newTable;
        threshold = newTable.length - (newTable.length >>> 2);
    }

    @SuppressWarnings("unchecked")
    private HashEntry<T>[] newTable(int capacity) {
        return (HashEntry<T>[]) new HashEntry[capacity];
    }

    private int bucketIndex(int hash, int capacity) {
        int spread = hash ^ (hash >>> 16);
        return spread & (capacity - 1);
    }

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
        E key;
        Node<E> node;
        int hash;
        HashEntry<E> next;

        HashEntry(E key, Node<E> node, int hash) {
            this.key = key;
            this.node = node;
            this.hash = hash;
        }
    }
}
