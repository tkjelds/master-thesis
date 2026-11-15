import java.lang.reflect.Array;

@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T item;
        final int unused = 0;
        int hash;
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
    private Node<T> head;
    private Node<T> tail;
    private int size;
    private Node<T>[] table;
    private int threshold;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        table = (Node<T>[]) new Node[16];
        threshold = (int) (table.length * 0.75);
    }

    private static int spread(Object o) {
        int h = o.hashCode();
        return h ^ (h >>> 16);
    }

    private static void requireNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException("item must not be null");
        }
    }

    // ---- helpers; caller must hold lock ----

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
        Node<T> cur = table[idx];
        Node<T> prev = null;
        while (cur != null) {
            if (cur == node) {
                if (prev == null) {
                    table[idx] = cur.hashNext;
                } else {
                    prev.hashNext = cur.hashNext;
                }
                cur.hashNext = null;
                return;
            }
            prev = cur;
            cur = cur.hashNext;
        }
    }

    @SuppressWarnings("unchecked")
    private void growIfNeeded() {
        if (size + 1 <= threshold) {
            return;
        }
        Node<T>[] old = table;
        int newLen = old.length << 1;
        if (newLen <= 0) {
            return; // cannot grow further
        }
        Node<T>[] nt = (Node<T>[]) new Node[newLen];
        int mask = newLen - 1;
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
        threshold = (int) (newLen * 0.75);
    }

    private Node<T> nodeAt(int index) {
        if (index < size / 2) {
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

    private void linkBefore(Node<T> node, Node<T> succ) {
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
            Node<T> p = succ.prev;
            node.prev = p;
            node.next = succ;
            succ.prev = node;
            if (p == null) {
                head = node;
            } else {
                p.next = node;
            }
        }
    }

    private T unlinkNode(Node<T> node) {
        T item = node.item;
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
        hashRemove(node);
        node.prev = null;
        node.next = null;
        node.item = null;
        size--;
        return item;
    }

    // ---- public operations ----

    public boolean add(T item) {
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            if (findNode(item, h) != null) {
                return false;
            }
            growIfNeeded();
            Node<T> node = new Node<T>(item, h);
            linkBefore(node, null);
            hashInsert(node);
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
            growIfNeeded();
            Node<T> node = new Node<T>(item, h);
            linkBefore(node, head);
            hashInsert(node);
            size++;
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (findNode(item, h) != null) {
                return false;
            }
            growIfNeeded();
            Node<T> succ = (index == size) ? null : nodeAt(index);
            Node<T> node = new Node<T>(item, h);
            linkBefore(node, succ);
            hashInsert(node);
            size++;
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
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            Node<T> node = nodeAt(index);
            Node<T> existing = findNode(item, h);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("Equal item exists at another position");
            }
            T old = node.item;
            if (existing == node) {
                // equal hash and equality: same bucket, just swap representative
                node.item = item;
            } else {
                hashRemove(node);
                node.item = item;
                node.hash = h;
                hashInsert(node);
            }
            return old;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return unlinkNode(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        int h = spread(item);
        synchronized (lock) {
            Node<T> node = findNode(item, h);
            if (node == null) {
                return false;
            }
            unlinkNode(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlinkNode(head);
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (tail == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlinkNode(tail);
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
            int i = 0;
            for (Node<T> n = head; n != null; n = n.next) {
                result[i++] = n.item;
            }
            return result;
        }
    }
}
