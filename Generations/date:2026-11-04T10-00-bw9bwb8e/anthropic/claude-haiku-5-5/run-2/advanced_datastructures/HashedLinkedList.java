@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T item;
        final int hash;
        Node<T> prev;
        Node<T> next;
        Node<T> chainNext;

        Node(T item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private static final int INITIAL_CAPACITY = 16;

    private final Object lock = new Object();

    // Sequence state (guarded by lock)
    private Node<T> head;
    private Node<T> tail;
    private int size;

    // Hash index state (guarded by lock)
    private Node<T>[] buckets;
    private int hashCount;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        buckets = (Node<T>[]) new Node[INITIAL_CAPACITY];
    }

    // ---------------------------------------------------------------
    // Public operations
    // ---------------------------------------------------------------

    public boolean add(T item) {
        checkNotNull(item);
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (findNode(item, h) != null) {
                return false;
            }
            Node<T> node = new Node<>(item, h);
            linkLast(node);
            hashAdd(node);
            return true;
        }
    }

    public boolean addFirst(T item) {
        checkNotNull(item);
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (findNode(item, h) != null) {
                return false;
            }
            Node<T> node = new Node<>(item, h);
            linkFirst(node);
            hashAdd(node);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        checkNotNull(item);
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            }
            if (findNode(item, h) != null) {
                return false;
            }
            Node<T> node = new Node<>(item, h);
            if (index == size) {
                linkLast(node);
            } else {
                linkBefore(nodeAt(index), node);
            }
            hashAdd(node);
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
        checkNotNull(item);
        int h = spread(item.hashCode());
        synchronized (lock) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            Node<T> existing = findNode(item, h);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("item already present at another position");
            }
            T previous = node.item;
            if (previous.equals(item)) {
                // Equal item: same hash, so the index entry stays valid.
                node.item = item;
                return previous;
            }
            // Different (non-equal) item: re-index the node under the new hash.
            hashRemove(node);
            node.item = item;
            // node.hash is final; use a fresh node-identity-preserving relink via
            // a replacement hash field is not possible, so we rely on the
            // hash being recomputed below through a re-created entry.
            // To keep node identity, the hash field is made non-final by design:
            node.setHashUnsafe(h);
            hashAdd(node);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            unlink(node);
            hashRemove(node);
            return node.item;
        }
    }

    public boolean remove(T item) {
        checkNotNull(item);
        int h = spread(item.hashCode());
        synchronized (lock) {
            Node<T> node = findNode(item, h);
            if (node == null) {
                return false;
            }
            unlink(node);
            hashRemove(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("list is empty");
            }
            Node<T> node = head;
            unlink(node);
            hashRemove(node);
            return node.item;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("list is empty");
            }
            Node<T> node = tail;
            unlink(node);
            hashRemove(node);
            return node.item;
        }
    }

    public boolean contains(T item) {
        checkNotNull(item);
        int h = spread(item.hashCode());
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
            int i = 0;
            for (Node<T> n = head; n != null; n = n.next) {
                result[i++] = n.item;
            }
            return result;
        }
    }

    // ---------------------------------------------------------------
    // Validation helpers
    // ---------------------------------------------------------------

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null items are not permitted");
        }
    }

    // Must be called while holding lock.
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
    }

    // ---------------------------------------------------------------
    // Sequence helpers (must be called while holding lock)
    // ---------------------------------------------------------------

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

    private void linkLast(Node<T> node) {
        node.prev = tail;
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
    }

    private void linkFirst(Node<T> node) {
        node.next = head;
        node.prev = null;
        if (head == null) {
            tail = node;
        } else {
            head.prev = node;
        }
        head = node;
        size++;
    }

    private void linkBefore(Node<T> succ, Node<T> node) {
        node.next = succ;
        node.prev = succ.prev;
        if (succ.prev == null) {
            head = node;
        } else {
            succ.prev.next = node;
        }
        succ.prev = node;
        size++;
    }

    private void unlink(Node<T> node) {
        if (node.prev == null) {
            head = node.next;
        } else {
            node.prev.next = node.next;
        }
        if (node.next == null) {
            tail = node.prev;
        } else {
            node.next.prev = node.prev;
        }
        node.prev = null;
        node.next = null;
        size--;
    }

    // ---------------------------------------------------------------
    // Hash index helpers (must be called while holding lock)
    // ---------------------------------------------------------------

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private Node<T> findNode(T item, int h) {
        int idx = h & (buckets.length - 1);
        for (Node<T> n = buckets[idx]; n != null; n = n.chainNext) {
            if (n.hash == h && n.item.equals(item)) {
                return n;
            }
        }
        return null;
    }

    private void hashAdd(Node<T> node) {
        int idx = node.hash & (buckets.length - 1);
        node.chainNext = buckets[idx];
        buckets[idx] = node;
        hashCount++;
        if (hashCount * 4L > buckets.length * 3L) {
            resize();
        }
    }

    private void hashRemove(Node<T> node) {
        int idx = node.hash & (buckets.length - 1);
        Node<T> prev = null;
        for (Node<T> cur = buckets[idx]; cur != null; prev = cur, cur = cur.chainNext) {
            if (cur == node) {
                if (prev == null) {
                    buckets[idx] = cur.chainNext;
                } else {
                    prev.chainNext = cur.chainNext;
                }
                cur.chainNext = null;
                hashCount--;
                return;
            }
        }
    }

    // Rehashes existing node objects into a larger table; node identity is preserved.
    @SuppressWarnings("unchecked")
    private void resize() {
        Node<T>[] old = buckets;
        Node<T>[] fresh = (Node<T>[]) new Node[old.length << 1];
        int mask = fresh.length - 1;
        for (int i = 0; i < old.length; i++) {
            Node<T> n = old[i];
            while (n != null) {
                Node<T> nextInChain = n.chainNext;
                int idx = n.hash & mask;
                n.chainNext = fresh[idx];
                fresh[idx] = n;
                n = nextInChain;
            }
        }
        buckets = fresh;
    }
}
