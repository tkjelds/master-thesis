@HashedLinkedList.ThreadSafe
public final class HashedLinkedList<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        final int hash;
        T item;
        Node<T> prev;
        Node<T> next;
        Node<T> chain;

        Node(T item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private static final int INITIAL_CAPACITY = 16;

    private final Object lock = new Object();

    private Node<T> head;
    private Node<T> tail;
    private int size;

    private Node<T>[] table;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        table = (Node<T>[]) new Node[INITIAL_CAPACITY];
    }

    // ---------- Public operations ----------

    public boolean add(T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (findNode(item) != null) {
                return false;
            }
            Node<T> n = new Node<T>(item, hashOf(item));
            linkLast(n);
            tableInsert(n);
            afterGrow();
            return true;
        }
    }

    public boolean addFirst(T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (findNode(item) != null) {
                return false;
            }
            Node<T> n = new Node<T>(item, hashOf(item));
            linkFirst(n);
            tableInsert(n);
            afterGrow();
            return true;
        }
    }

    public boolean insert(int index, T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
            }
            if (findNode(item) != null) {
                return false;
            }
            Node<T> n = new Node<T>(item, hashOf(item));
            if (index == size) {
                linkLast(n);
            } else {
                Node<T> succ = nodeAt(index);
                linkBefore(succ, n);
            }
            tableInsert(n);
            afterGrow();
            return true;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        checkNotNull(item);
        synchronized (lock) {
            checkIndex(index);
            Node<T> target = nodeAt(index);
            Node<T> dup = findNode(item);
            if (dup != null && dup != target) {
                throw new IllegalArgumentException("item already present at another position");
            }
            T previous = target.item;
            if (previous != item) {
                tableRemove(target);
                target.item = item;
                // Equal items have equal hashes, so the stored hash remains valid.
                tableInsert(target);
            }
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkIndex(index);
            Node<T> n = nodeAt(index);
            unlink(n);
            tableRemove(n);
            size--;
            T item = n.item;
            n.item = null;
            return item;
        }
    }

    public boolean remove(T item) {
        checkNotNull(item);
        synchronized (lock) {
            Node<T> n = findNode(item);
            if (n == null) {
                return false;
            }
            unlink(n);
            tableRemove(n);
            size--;
            n.item = null;
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("list is empty");
            }
            Node<T> n = head;
            unlink(n);
            tableRemove(n);
            size--;
            T item = n.item;
            n.item = null;
            return item;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (tail == null) {
                throw new IllegalStateException("list is empty");
            }
            Node<T> n = tail;
            unlink(n);
            tableRemove(n);
            size--;
            T item = n.item;
            n.item = null;
            return item;
        }
    }

    public boolean contains(T item) {
        checkNotNull(item);
        synchronized (lock) {
            return findNode(item) != null;
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
            int i = 0;
            for (Node<T> n = head; n != null; n = n.next) {
                result[i++] = n.item;
            }
            return result;
        }
    }

    // ---------- Validation helpers ----------

    private static void checkNotNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item must not be null");
        }
    }

    // Must be called while holding lock.
    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    // ---------- Linked list helpers (must hold lock) ----------

    // Must be called while holding lock; index must be valid.
    private Node<T> nodeAt(int index) {
        if (index < (size >>> 1)) {
            Node<T> n = head;
            for (int i = 0; i < index; i++) {
                n = n.next;
            }
            return n;
        } else {
            Node<T> n = tail;
            for (int i = size - 1; i > index; i--) {
                n = n.prev;
            }
            return n;
        }
    }

    private void linkFirst(Node<T> n) {
        n.next = head;
        n.prev = null;
        if (head == null) {
            tail = n;
        } else {
            head.prev = n;
        }
        head = n;
        size++;
    }

    private void linkLast(Node<T> n) {
        n.prev = tail;
        n.next = null;
        if (tail == null) {
            head = n;
        } else {
            tail.next = n;
        }
        tail = n;
        size++;
    }

    private void linkBefore(Node<T> succ, Node<T> n) {
        n.next = succ;
        n.prev = succ.prev;
        if (succ.prev == null) {
            head = n;
        } else {
            succ.prev.next = n;
        }
        succ.prev = n;
        size++;
    }

    // Unlinks from sequence only; does not change size or hash table.
    private void unlink(Node<T> n) {
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

    // ---------- Hash table helpers (must hold lock) ----------

    private static int hashOf(Object item) {
        int h = item.hashCode();
        return h ^ (h >>> 16);
    }

    private static int indexFor(int hash, int length) {
        return hash & (length - 1);
    }

    private Node<T> findNode(T item) {
        int h = hashOf(item);
        int idx = indexFor(h, table.length);
        for (Node<T> c = table[idx]; c != null; c = c.chain) {
            if (c.hash == h && (c.item == item || c.item.equals(item))) {
                return c;
            }
        }
        return null;
    }

    // Inserts node into the table; node must not already be present.
    private void tableInsert(Node<T> n) {
        int idx = indexFor(n.hash, table.length);
        n.chain = table[idx];
        table[idx] = n;
    }

    // Removes the exact node (identity) from the table.
    private void tableRemove(Node<T> n) {
        int idx = indexFor(n.hash, table.length);
        Node<T> prev = null;
        for (Node<T> c = table[idx]; c != null; prev = c, c = c.chain) {
            if (c == n) {
                if (prev == null) {
                    table[idx] = c.chain;
                } else {
                    prev.chain = c.chain;
                }
                c.chain = null;
                return;
            }
        }
    }

    // Grows the table when load factor exceeds 3/4. Nodes are relinked, not recreated,
    // so node identity is preserved.
    private void afterGrow() {
        if (size > table.length - (table.length >>> 2)) {
            resize();
        }
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        Node<T>[] old = table;
        Node<T>[] newTable = (Node<T>[]) new Node[old.length << 1];
        int newLen = newTable.length;
        for (int i = 0; i < old.length; i++) {
            Node<T> c = old[i];
            while (c != null) {
                Node<T> next = c.chain;
                int idx = indexFor(c.hash, newLen);
                c.chain = newTable[idx];
                newTable[idx] = c;
                c = next;
            }
        }
        table = newTable;
    }
}
