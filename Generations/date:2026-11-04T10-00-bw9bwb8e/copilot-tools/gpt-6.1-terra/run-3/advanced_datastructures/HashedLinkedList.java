@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private static final class Node {
        Object item;
        int hash;
        Node previous;
        Node next;
        Node hashNext;

        Node(Object item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();
    private Node[] table = new Node[INITIAL_CAPACITY];
    private Node first;
    private Node last;
    private int size;

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
            checkPositionIndex(index);
            return insertBefore(index == size ? null : nodeAt(index), item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return itemOf(nodeAt(index));
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            checkElementIndex(index);
            int hash = hashOf(item);
            Node node = nodeAt(index);
            Node existing = find(item, hash);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("Item already exists");
            }

            T previous = itemOf(node);
            if (node.hash != hash) {
                removeFromTable(node);
                node.hash = hash;
                int bucket = bucketIndex(hash, table.length);
                node.hashNext = table[bucket];
                table[bucket] = node;
            }
            node.item = item;
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
            int hash = hashOf(item);
            Node node = find(item, hash);
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
            Node node = first;
            for (int i = 0; i < result.length; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private boolean insertBefore(Node successor, T item) {
        int hash = hashOf(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is too large");
        }

        Node node = new Node(item, hash);
        ensureCapacityForInsertion();

        int bucket = bucketIndex(hash, table.length);
        node.hashNext = table[bucket];
        table[bucket] = node;

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
        return true;
    }

    private T unlink(Node node) {
        removeFromTable(node);

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
        return itemOf(node);
    }

    private Node find(Object item, int hash) {
        Node node = table[bucketIndex(hash, table.length)];
        while (node != null) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
            node = node.hashNext;
        }
        return null;
    }

    private void removeFromTable(Node node) {
        int bucket = bucketIndex(node.hash, table.length);
        Node current = table[bucket];
        Node previous = null;
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

    private void ensureCapacityForInsertion() {
        int capacity = table.length;
        if (capacity == MAX_CAPACITY || size < capacity - (capacity >>> 2)) {
            return;
        }

        Node[] newTable = new Node[capacity << 1];
        for (int i = 0; i < capacity; i++) {
            Node node = table[i];
            while (node != null) {
                Node next = node.hashNext;
                int bucket = bucketIndex(node.hash, newTable.length);
                node.hashNext = newTable[bucket];
                newTable[bucket] = node;
                node = next;
            }
        }
        table = newTable;
    }

    private Node nodeAt(int index) {
        if (index < size / 2) {
            Node node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }

        Node node = last;
        for (int i = size - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private static int hashOf(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int bucketIndex(int hash, int capacity) {
        return hash & (capacity - 1);
    }

    @SuppressWarnings("unchecked")
    private T itemOf(Node node) {
        return (T) node.item;
    }
}
