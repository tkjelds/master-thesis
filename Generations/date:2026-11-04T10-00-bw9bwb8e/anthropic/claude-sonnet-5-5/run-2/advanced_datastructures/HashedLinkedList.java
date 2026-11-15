@HashedLinkedList.ThreadSafe
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

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static int hashOf(Object item) {
        return spread(item.hashCode());
    }

    // ---- hash table helpers (call with lock held) ----

    private Node<T> find(Object item, int hash) {
        Node<T> n = table[hash & (table.length - 1)];
        while (n != null) {
            if (n.hash == hash && (n.item == item || item.equals(n.item))) {
                return n;
            }
            n = n.hashNext;
        }
        return null;
    }

    private void hashInsert(Node<T> node) {
        if (size >= threshold) {
            resize();
        }
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
    private void resize() {
        Node<T>[] old = table;
        int newLen = old.length * 2;
        Node<T>[] nt = (Node<T>[]) new Node[newLen];
        for (int i = 0; i < old.length; i++) {
            Node<T> n = old[i];
            while (n != null) {
                Node<T> nx = n.hashNext;
                int idx = n.hash & (newLen - 1);
                n.hashNext = nt[idx];
                nt[idx] = n;
                n = nx;
            }
        }
        table = nt;
        threshold = (int) (newLen * 0.75);
    }

    // ---- list helpers (call with lock held) ----

    private void linkLast(Node<T> node) {
        node.prev = tail;
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
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
    }

    private void linkBefore(Node<T> node, Node<T> succ) {
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

    private void unlink(Node<T> node) {
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

    private Node<T> nodeAt(int index) {
        Node<T> n;
        if (index < (size >> 1)) {
            n = head;
            for (int i = 0; i < index; i++) {
                n = n.next;
            }
        } else {
            n = tail;
            for (int i = size - 1; i > index; i--) {
                n = n.prev;
            }
        }
        return n;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private T removeNode(Node<T> node) {
        hashRemove(node);
        unlink(node);
        size--;
        T item = node.item;
        return item;
    }

    // ---- public operations ----

    public boolean add(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            if (find(item, h) != null) return false;
            Node<T> node = new Node<T>(item, h);
            hashInsert(node);
            linkLast(node);
            size++;
            return true;
        }
    }

    public boolean addFirst(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            if (find(item, h) != null) return false;
            Node<T> node = new Node<T>(item, h);
            hashInsert(node);
            linkFirst(node);
            size++;
            return true;
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (find(item, h) != null) return false;
            Node<T> node = new Node<T>(item, h);
            Node<T> succ = (index == size) ? null : nodeAt(index);
            hashInsert(node);
            if (succ == null) {
                linkLast(node);
            } else {
                linkBefore(node, succ);
            }
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
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            Node<T> existing = find(item, h);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("Item already present at another position");
            }
            T old = node.item;
            if (existing == node) {
                // equal item, equal hash: same node and bucket stay valid
                node.item = item;
                return old;
            }
            // Different item: node's hash is final, so replace node in place.
            Node<T> repl = new Node<T>(item, h);
            hashRemove(node);
            // swap into list
            repl.prev = node.prev;
            repl.next = node.next;
            if (node.prev == null) {
                head = repl;
            } else {
                node.prev.next = repl;
            }
            if (node.next == null) {
                tail = repl;
            } else {
                node.next.prev = repl;
            }
            node.prev = null;
            node.next = null;
            // size unchanged; resize check uses size which is fine
            hashInsert(repl);
            return old;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return removeNode(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            Node<T> node = find(item, h);
            if (node == null) return false;
            removeNode(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (head == null) throw new IllegalStateException("List is empty");
            return removeNode(head);
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (tail == null) throw new IllegalStateException("List is empty");
            return removeNode(tail);
        }
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = hashOf(item);
        synchronized (lock) {
            return find(item, h) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] arr = new Object[size];
            int i = 0;
            for (Node<T> n = head; n != null; n = n.next) {
                arr[i++] = n.item;
            }
            return arr;
        }
    }
}
