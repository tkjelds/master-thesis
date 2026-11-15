@HashedLinkedList.ThreadSafe
public final class HashedLinkedList<T> {
    @interface ThreadSafe {}


    private static final int INITIAL_CAPACITY = 16;

    private final class Node {
        T item;
        int hash;
        Node prev;
        Node next;
        Node bucketNext;
    }

    private final Object lock = new Object();
    private Node head;
    private Node tail;
    private int size;

    private Node[] table;
    private int tableCount;

    public HashedLinkedList() {
        table = newTable(INITIAL_CAPACITY);
    }

    // ---------------------------------------------------------------------
    // Public operations
    // ---------------------------------------------------------------------

    public boolean add(T item) {
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            if (findNode(item, h) != null) {
                return false;
            }
            Node n = newNode(item, h);
            linkLast(n);
            tableInsert(n);
            size++;
            return true;
        }
    }

    public boolean addFirst(T item) {
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            if (findNode(item, h) != null) {
                return false;
            }
            Node n = newNode(item, h);
            linkFirst(n);
            tableInsert(n);
            size++;
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index " + index + " not in [0, " + size + "]");
            }
            if (findNode(item, h) != null) {
                return false;
            }
            Node n = newNode(item, h);
            if (index == size) {
                linkLast(n);
            } else if (index == 0) {
                linkFirst(n);
            } else {
                linkBefore(nodeAt(index), n);
            }
            tableInsert(n);
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
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            checkElementIndex(index);
            Node n = nodeAt(index);
            Node existing = findNode(item, h);
            if (existing != null && existing != n) {
                throw new IllegalArgumentException("item already present at another position");
            }
            T old = n.item;
            if (existing == n) {
                // Equal item at the same position: representative replaced, hash unchanged.
                n.item = item;
                return old;
            }
            // Distinct item: the hash may change, so relocate the node in the table.
            tableRemove(n);
            n.item = item;
            n.hash = h;
            tableInsert(n);
            return old;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            Node n = nodeAt(index);
            T item = n.item;
            unlink(n);
            tableRemove(n);
            size--;
            return item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            Node n = findNode(item, h);
            if (n == null) {
                return false;
            }
            unlink(n);
            tableRemove(n);
            size--;
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("list is empty");
            }
            Node n = head;
            T item = n.item;
            unlink(n);
            tableRemove(n);
            size--;
            return item;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (tail == null) {
                throw new IllegalStateException("list is empty");
            }
            Node n = tail;
            T item = n.item;
            unlink(n);
            tableRemove(n);
            size--;
            return item;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            return findNode(item, h) != null;
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
            Node n = head;
            int i = 0;
            while (n != null) {
                result[i++] = n.item;
                n = n.next;
            }
            return result;
        }
    }

    // ---------------------------------------------------------------------
    // Validation helpers (no mutation)
    // ---------------------------------------------------------------------

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("null items are not permitted");
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + " not in [0, " + size + ")");
        }
    }

    // ---------------------------------------------------------------------
    // Linked list internals (caller must hold lock)
    // ---------------------------------------------------------------------

    private Node newNode(T item, int h) {
        Node n = new Node();
        n.item = item;
        n.hash = h;
        return n;
    }

    private void linkLast(Node n) {
        n.prev = tail;
        n.next = null;
        if (tail == null) {
            head = n;
        } else {
            tail.next = n;
        }
        tail = n;
    }

    private void linkFirst(Node n) {
        n.next = head;
        n.prev = null;
        if (head == null) {
            tail = n;
        } else {
            head.prev = n;
        }
        head = n;
    }

    private void linkBefore(Node succ, Node n) {
        n.next = succ;
        n.prev = succ.prev;
        if (succ.prev == null) {
            head = n;
        } else {
            succ.prev.next = n;
        }
        succ.prev = n;
    }

    private void unlink(Node n) {
        if (n.prev == null) {
            head = n.next;
        } else {
            n.prev.next = n.next;
        }
        if (n.next == null) {
            tail = n.prev;
        } else {
            n.next.prev = n.prev;
        }
        n.prev = null;
        n.next = null;
    }

    private Node nodeAt(int index) {
        if (index < (size >>> 1)) {
            Node n = head;
            for (int i = 0; i < index; i++) {
                n = n.next;
            }
            return n;
        } else {
            Node n = tail;
            for (int i = size - 1; i > index; i--) {
                n = n.prev;
            }
            return n;
        }
    }

    // ---------------------------------------------------------------------
    // Hash table internals (caller must hold lock)
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Node[] newTable(int capacity) {
        return (Node[]) new HashedLinkedList.this.new Node[capacity];
    }

    private static int spread(Object o) {
        int h = o.hashCode();
        h *= 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    private Node findNode(Object item, int h) {
        Node n = table[h & (table.length - 1)];
        while (n != null) {
            if (n.hash == h && item.equals(n.item)) {
                return n;
            }
            n = n.bucketNext;
        }
        return null;
    }

    private void tableInsert(Node n) {
        int idx = n.hash & (table.length - 1);
        n.bucketNext = table[idx];
        table[idx] = n;
        tableCount++;
        if (tableCount > table.length * 3 / 4) {
            resize(table.length << 1);
        }
    }

    private void tableRemove(Node n) {
        int idx = n.hash & (table.length - 1);
        Node prev = null;
        Node cur = table[idx];
        while (cur != null) {
            if (cur == n) {
                if (prev == null) {
                    table[idx] = cur.bucketNext;
                } else {
                    prev.bucketNext = cur.bucketNext;
                }
                cur.bucketNext = null;
                tableCount--;
                return;
            }
            prev = cur;
            cur = cur.bucketNext;
        }
    }

    // Rehashes existing nodes into a larger table; node identity is preserved.
    private void resize(int newCapacity) {
        Node[] newTable = newTable(newCapacity);
        int mask = newCapacity - 1;
        for (int i = 0; i < table.length; i++) {
            Node n = table[i];
            while (n != null) {
                Node next = n.bucketNext;
                int idx = n.hash & mask;
                n.bucketNext = newTable[idx];
                newTable[idx] = n;
                n = next;
            }
        }
        table = newTable;
    }
}
