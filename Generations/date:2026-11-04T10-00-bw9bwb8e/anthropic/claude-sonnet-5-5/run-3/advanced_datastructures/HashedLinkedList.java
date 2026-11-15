import java.util.*; is deliberately absent; @HashedLinkedList.ThreadSafe
the file uses no imports.

public class HashedLinkedList<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T item;
        final int hash;
        Node<T> prev;
        Node<T> next;
        Node<T> hashNext;

        Node(T item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();

    // All fields below are guarded by lock.
    private Node<T>[] table;
    private Node<T> head;
    private Node<T> tail;
    private int size;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        table = (Node<T>[]) new Node[16];
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static int hashOf(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        return spread(item.hashCode());
    }

    // ---- hash table helpers (call with lock held) ----

    private Node<T> findNode(Object item, int hash) {
        Node<T> n = table[hash & (table.length - 1)];
        while (n != null) {
            if (n.hash == hash && (n.item == item || n.item.equals(item))) {
                return n;
            }
            n = n.hashNext;
        }
        return null;
    }

    private void hashInsert(Node<T> node) {
        int idx = node.hash & (table.length - 1);
        node.hashNext = table[idx];
        table[idx] = node;
    }

    private void hashRemove(Node<T> node) {
        int idx = node.hash & (table.length - 1);
        Node<T> n = table[idx];
        Node<T> p = null;
        while (n != null) {
            if (n == node) {
                if (p == null) {
                    table[idx] = n.hashNext;
                } else {
                    p.hashNext = n.hashNext;
                }
                n.hashNext = null;
                return;
            }
            p = n;
            n = n.hashNext;
        }
    }

    @SuppressWarnings("unchecked")
    private void growIfNeeded() {
        // load factor 0.75
        if (size <= (table.length >> 1) + (table.length >> 2)) {
            return;
        }
        Node<T>[] old = table;
        Node<T>[] nt = (Node<T>[]) new Node[old.length * 2];
        int mask = nt.length - 1;
        for (int i = 0; i < old.length; i++) {
            Node<T> n = old[i];
            while (n != null) {
                Node<T> nx = n.hashNext;
                int idx = n.hash & mask;
                n.hashNext = nt[idx];
                nt[idx] = n;
                n = nx;
            }
        }
        table = nt;
    }

    // ---- list helpers (call with lock held) ----

    private void linkBefore(Node<T> node, Node<T> succ) {
        // succ == null means append at tail
        if (succ == null) {
            node.prev = tail;
            node.next = null;
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
        } else {
            Node<T> pred = succ.prev;
            node.prev = pred;
            node.next = succ;
            succ.prev = node;
            if (pred == null) {
                head = node;
            } else {
                pred.next = node;
            }
        }
    }

    private void unlinkNode(Node<T> node) {
        Node<T> p = node.prev;
        Node<T> n = node.next;
        if (p == null) {
            head = n;
        } else {
            p.next = n;
        }
        if (n == null) {
            tail = p;
        } else {
            n.prev = p;
        }
        node.prev = null;
        node.next = null;
    }

    private void removeNode(Node<T> node) {
        hashRemove(node);
        unlinkNode(node);
        size--;
    }

    private Node<T> nodeAt(int index) {
        if (index < (size >> 1)) {
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

    // ---- public operations ----

    public boolean add(T item) {
        int h = hashOf(item);
        synchronized (lock) {
            if (findNode(item, h) != null) {
                return false;
            }
            Node<T> node = new Node<T>(item, h);
            linkBefore(node, null);
            size++;
            hashInsert(node);
            growIfNeeded();
            return true;
        }
    }

    public boolean addFirst(T item) {
        int h = hashOf(item);
        synchronized (lock) {
            if (findNode(item, h) != null) {
                return false;
            }
            Node<T> node = new Node<T>(item, h);
            linkBefore(node, head);
            size++;
            hashInsert(node);
            growIfNeeded();
            return true;
        }
    }

    public boolean insert(int index, T item) {
        int h = hashOf(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (findNode(item, h) != null) {
                return false;
            }
            Node<T> succ = (index == size) ? null : nodeAt(index);
            Node<T> node = new Node<T>(item, h);
            linkBefore(node, succ);
            size++;
            hashInsert(node);
            growIfNeeded();
            return true;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        int h = hashOf(item);
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            Node<T> existing = findNode(item, h);
            Node<T> target = nodeAt(index);
            if (existing != null && existing != target) {
                throw new IllegalArgumentException("Item already present at another position");
            }
            T old = target.item;
            // Equal items have equal hashes, so the node stays in its bucket when existing == target.
            if (existing == target) {
                target.item = item;
                return old;
            }
            // Different item: node's hash must change, so replace the node in the hash table
            // while preserving its list position by creating a new node.
            Node<T> repl = new Node<T>(item, h);
            hashRemove(target);
            Node<T> p = target.prev;
            Node<T> n = target.next;
            repl.prev = p;
            repl.next = n;
            if (p == null) {
                head = repl;
            } else {
                p.next = repl;
            }
            if (n == null) {
                tail = repl;
            } else {
                n.prev = repl;
            }
            target.prev = null;
            target.next = null;
            hashInsert(repl);
            return old;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            Node<T> node = nodeAt(index);
            T old = node.item;
            removeNode(node);
            return old;
        }
    }

    public boolean remove(T item) {
        int h = hashOf(item);
        synchronized (lock) {
            Node<T> node = findNode(item, h);
            if (node == null) {
                return false;
            }
            removeNode(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("List is empty");
            }
            Node<T> node = head;
            T old = node.item;
            removeNode(node);
            return old;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("List is empty");
            }
            Node<T> node = tail;
            T old = node.item;
            removeNode(node);
            return old;
        }
    }

    public boolean contains(T item) {
        int h = hashOf(item);
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
            Node<T> n = head;
            int i = 0;
            while (n != null) {
                result[i++] = n.item;
                n = n.next;
            }
            return result;
        }
    }
}
