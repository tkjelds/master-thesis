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

    private final Object lock = new Object();
    private Node<T>[] table = newTable(INITIAL_CAPACITY);
    private int threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    private Node<T> first;
    private Node<T> last;
    private int size;

    public HashedLinkedList() {
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
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            return insertBefore(index == size ? null : nodeAt(index), item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            Node<T> node = nodeAt(index);
            int hash = hashOf(item);
            Node<T> existing = find(item, hash);

            if (existing != null && existing != node) {
                throw new IllegalArgumentException("Item already exists");
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
            return unlink(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            int hash = hashOf(item);
            Node<T> node = find(item, hash);
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        }
    }

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
            int hash = hashOf(item);
            return find(item, hash) != null;
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

    // All instance helpers below are called while holding lock.
    private int hashOf(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private Node<T> find(T item, int hash) {
        int bucket = hash & (table.length - 1);
        for (Node<T> node = table[bucket]; node != null; node = node.hashNext) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
        }
        return null;
    }

    private Node<T> nodeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index);
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

    private boolean insertBefore(Node<T> successor, T item) {
        int hash = hashOf(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == 0x7fffffff) {
            throw new IllegalStateException("Maximum list size reached");
        }

        Node<T> node = new Node<T>(item, hash);
        ensureCapacityForInsert();

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

        T item = node.item;
        node.previous = null;
        node.next = null;
        node.item = null;
        size--;
        return item;
    }

    private void addHashEntry(Node<T> node) {
        int bucket = node.hash & (table.length - 1);
        node.hashNext = table[bucket];
        table[bucket] = node;
    }

    private void removeHashEntry(Node<T> node) {
        int bucket = node.hash & (table.length - 1);
        Node<T> previous = null;
        Node<T> current = table[bucket];

        while (current != node) {
            previous = current;
            current = current.hashNext;
        }

        if (previous == null) {
            table[bucket] = node.hashNext;
        } else {
            previous.hashNext = node.hashNext;
        }
        node.hashNext = null;
    }

    private void ensureCapacityForInsert() {
        if (size < threshold) {
            return;
        }

        if (table.length == MAX_CAPACITY) {
            threshold = 0x7fffffff;
            return;
        }

        Node<T>[] expanded = newTable(table.length << 1);
        int mask = expanded.length - 1;

        for (int i = 0; i < table.length; i++) {
            Node<T> node = table[i];
            while (node != null) {
                Node<T> next = node.hashNext;
                int bucket = node.hash & mask;
                node.hashNext = expanded[bucket];
                expanded[bucket] = node;
                node = next;
            }
        }

        table = expanded;
        threshold = table.length - (table.length >>> 2);
    }

    @SuppressWarnings("unchecked")
    private static <E> Node<E>[] newTable(int capacity) {
        return (Node<E>[]) new Node<?>[capacity];
    }
}
