@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}


    private static final class Node {
        Object item;
        int hash;
        int index;
        Node next;

        Node(Object item, int hash, int index) {
            this.item = item;
            this.hash = hash;
            this.index = index;
        }
    }

    private final Object lock = new Object();

    // Guarded by lock
    private Node[] nodes;   // sequence order
    private Node[] table;   // hash buckets (chaining), length is a power of two
    private int size;

    public HashedArrayList() {
        nodes = new Node[10];
        table = new Node[16];
        size = 0;
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private Node lookup(Object item, int hash) {
        Node n = table[hash & (table.length - 1)];
        while (n != null) {
            if (n.hash == hash && (n.item == item || n.item.equals(item))) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    private void link(Node n) {
        int b = n.hash & (table.length - 1);
        n.next = table[b];
        table[b] = n;
    }

    private void unlink(Node n) {
        int b = n.hash & (table.length - 1);
        Node cur = table[b];
        Node prev = null;
        while (cur != null) {
            if (cur == n) {
                if (prev == null) {
                    table[b] = cur.next;
                } else {
                    prev.next = cur.next;
                }
                cur.next = null;
                return;
            }
            prev = cur;
            cur = cur.next;
        }
    }

    // Called before mutation; builds the new structures fully, then swaps.
    private void ensureCapacity() {
        if (size == nodes.length) {
            int newCap = nodes.length + (nodes.length >> 1) + 1;
            Node[] bigger = new Node[newCap];
            System.arraycopy(nodes, 0, bigger, 0, size);
            nodes = bigger;
        }
        if (size + 1 > (table.length >> 1) + (table.length >> 2)) {
            Node[] newTable = new Node[table.length << 1];
            int mask = newTable.length - 1;
            for (int i = 0; i < size; i++) {
                Node n = nodes[i];
                int b = n.hash & mask;
                n.next = newTable[b];
                newTable[b] = n;
            }
            table = newTable;
        }
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (lookup(item, h) != null) return false;
            ensureCapacity();
            Node n = new Node(item, h, size);
            nodes[size++] = n;
            link(n);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (lookup(item, h) != null) return false;
            ensureCapacity();
            System.arraycopy(nodes, index, nodes, index + 1, size - index);
            Node n = new Node(item, h, index);
            nodes[index] = n;
            size++;
            for (int i = index + 1; i < size; i++) {
                nodes[i].index = i;
            }
            link(n);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return (T) nodes[index].item;
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            Node existing = lookup(item, h);
            if (existing != null && existing.index != index) {
                throw new IllegalArgumentException("Equal item exists at another index");
            }
            Node old = nodes[index];
            Object prev = old.item;
            unlink(old);
            Node n = new Node(item, h, index);
            nodes[index] = n;
            link(n);
            return (T) prev;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            return (T) removeNodeAt(index).item;
        }
    }

    // Caller holds lock; index valid.
    private Node removeNodeAt(int index) {
        Node n = nodes[index];
        unlink(n);
        System.arraycopy(nodes, index + 1, nodes, index, size - index - 1);
        nodes[--size] = null;
        for (int i = index; i < size; i++) {
            nodes[i].index = i;
        }
        return n;
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            Node n = lookup(item, h);
            if (n == null) return false;
            removeNodeAt(n.index);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            return lookup(item, h) != null;
        }
    }

    public int indexOf(T item) {
        if (item == null) throw new NullPointerException("item");
        int h = spread(item.hashCode());
        synchronized (lock) {
            Node n = lookup(item, h);
            return n == null ? -1 : n.index;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[size];
            for (int i = 0; i < size; i++) {
                out[i] = nodes[i].item;
            }
            return out;
        }
    }
}
