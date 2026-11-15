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
        final Node<E> node;
        final int hash;
        Entry<E> next;

        Entry(E item, Node<E> node, int hash) {
            this.item = item;
            this.node = node;
            this.hash = hash;
        }
    }

    private Node<T> first;
    private Node<T> last;
    private int size;
    private Entry<T>[] table;
    private int threshold;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        table = (Entry<T>[]) new Entry<?>[INITIAL_CAPACITY];
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            int hash = spread(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            ensureCapacity(size + 1);
            Node<T> node = new Node<T>(item);
            addNodeBefore(null, node);
            addEntry(new Entry<T>(item, node, hash));
            size++;
            return true;
        }
    }

    public boolean addFirst(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            int hash = spread(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            ensureCapacity(size + 1);
            Node<T> node = new Node<T>(item);
            addNodeBefore(first, node);
            addEntry(new Entry<T>(item, node, hash));
            size++;
            return true;
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            checkPositionIndex(index);
            int hash = spread(item.hashCode());
            if (findEntry(item, hash) != null) {
                return false;
            }
            ensureCapacity(size + 1);
            Node<T> node = new Node<T>(item);
            Node<T> before = index == size ? null : nodeAt(index);
            addNodeBefore(before, node);
            addEntry(new Entry<T>(item, node, hash));
            size++;
            return true;
        }
    }

    public T get(int index) {
        synchronized (this) {
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            Node<T> node = nodeAt(index);
            int hash = spread(item.hashCode());
            Entry<T> existing = findEntry(item, hash);
            if (existing != null && existing.node != node) {
                throw new IllegalArgumentException();
            }

            T previous = node.item;
            if (existing == node.entry) {
                node.item = item;
                node.entry.item = item;
            } else {
                Entry<T> replacement = new Entry<T>(item, node, hash);
                removeEntry(node.entry);
                node.item = item;
                node.entry = replacement;
                addEntry(replacement);
            }
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (this) {
            return unlink(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            Entry<T> entry = findEntry(item, spread(item.hashCode()));
            if (entry == null) {
                return false;
            }
            unlink(entry.node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (this) {
            if (first == null) {
                throw new IllegalStateException();
            }
            return unlink(first);
        }
    }

    public T removeLast() {
        synchronized (this) {
            if (last == null) {
                throw new IllegalStateException();
            }
            return unlink(last);
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (this) {
            return findEntry(item, spread(item.hashCode())) != null;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            Object[] result = new Object[size];
            Node<T> current = first;
            for (int i = 0; i < size; i++) {
                result[i] = current.item;
                current = current.next;
            }
            return result;
        }
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private Entry<T> findEntry(T item, int hash) {
        for (Entry<T> entry = table[hash & (table.length - 1)];
             entry != null;
             entry = entry.next) {
            if (entry.hash == hash && item.equals(entry.item)) {
                return entry;
            }
        }
        return null;
    }

    private void addEntry(Entry<T> entry) {
        int bucket = entry.hash & (table.length - 1);
        entry.next = table[bucket];
        table[bucket] = entry;
        entry.node.entry = entry;
    }

    private void removeEntry(Entry<T> target) {
        int bucket = target.hash & (table.length - 1);
        Entry<T> previous = null;
        Entry<T> current = table[bucket];

        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    table[bucket] = current.next;
                } else {
                    previous.next = current.next;
                }
                target.next = null;
                target.node.entry = null;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity(int requiredSize) {
        if (requiredSize <= threshold || table.length >= (1 << 30)) {
            return;
        }

        Entry<T>[] oldTable = table;
        Entry<T>[] newTable = (Entry<T>[]) new Entry<?>[oldTable.length << 1];

        for (Entry<T> head : oldTable) {
            Entry<T> entry = head;
            while (entry != null) {
                Entry<T> next = entry.next;
                int bucket = entry.hash & (newTable.length - 1);
                entry.next = newTable[bucket];
                newTable[bucket] = entry;
                entry = next;
            }
        }

        table = newTable;
        threshold = newTable.length - (newTable.length >>> 2);
    }

    private void addNodeBefore(Node<T> before, Node<T> node) {
        if (before == null) {
            node.previous = last;
            node.next = null;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
        } else {
            node.previous = before.previous;
            node.next = before;
            if (before.previous == null) {
                first = node;
            } else {
                before.previous.next = node;
            }
            before.previous = node;
        }
    }

    private T unlink(Node<T> node) {
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

        T item = node.item;
        node.previous = null;
        node.next = null;
        node.entry = null;
        size--;
        return item;
    }

    private Node<T> nodeAt(int index) {
        checkElementIndex(index);
        if (index < (size >>> 1)) {
            Node<T> current = first;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current;
        } else {
            Node<T> current = last;
            for (int i = size - 1; i > index; i--) {
                current = current.previous;
            }
            return current;
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException();
        }
    }
}
