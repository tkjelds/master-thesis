@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> prev, next;

        Node(T item) { this.item = item; }
    }

    private final Node<T> header = new Node<>(null);
    private volatile int size;
    private Node<T>[] table;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        this.table = new Node[16];
    }

    private Node<T> getNode(int index) {
        synchronized (this) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            Node<T> p;
            if (index < size / 2) {
                for (p = header.next; index > 0; --index) p = p.next;
            } else {
                for (p = header.prev; index < size - 1; ++index) p = p.prev;
            }
            return p;
        }
    }

    private int hash(Object key) {
        int h = key.hashCode();
        h = (h ^ (h >>> 16)) & ~(1 << 31);
        return h;
    }

    private int hashIndex(int hash, Node<T>[] table) {
        return (hash & 0x7FFFFFFF) % table.length;
    }

    private Node<T> hashLookup(T item, Node<T>[] table) {
        int index = hashIndex(hash(item), table);
        for (Node<T> n = table[index]; n != null; n = n.next) {
            if (n.item.equals(item)) return n;
        }
        return null;
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException();
        return add(size, item);
    }

    public boolean addFirst(T item) {
        if (item == null) throw new NullPointerException();
        return add(0, item);
    }

    public boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();

        Node<T> pred = header;
        for (int i = 0; i < index; ++i) pred = pred.next;

        Node<T> successor = pred.next;
        Node<T> newNode = new Node<>(item);
        newNode.prev = pred;
        newNode.next = successor;
        pred.next = newNode;
        successor.prev = newNode;

        int hashIndex = hashIndex(hash(item), table);
        if (table[hashIndex] == null) {
            Node<T> newBucket = newNode;
            while (newBucket.next != null) newBucket = newBucket.next;
            table[hashIndex] = newBucket;
        } else {
            Node<T> bucket = table[hashIndex];
            while (bucket.next != null) bucket = bucket.next;
            bucket.next = newNode;
        }

        ++size;
        return true;
    }

    public T get(int index) {
        return getNode(index).item;
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();

        Node<T> node = getNode(index);
        T oldItem = node.item;
        if (!oldItem.equals(item)) {
            int hashIndexOld = hashIndex(hash(oldItem), table);
            Node<T> p = table[hashIndexOld];
            while (p != null && !p.item.equals(oldItem)) p = p.next;
            if (p == node) {
                int hashIndexNew = hashIndex(hash(item), table);
                if (hashIndexOld != hashIndexNew) {
                    p = null;
                    while (p != node) {
                        p = p.next;
                    }
                }
            }

            if (p == null || !p.item.equals(oldItem)) {
                throw new IllegalArgumentException("Cannot replace item at index " + index);
            }
        }

        node.item = item;
        return oldItem;
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();

        Node<T> node = getNode(index);
        T item = node.item;

        int hashIndex = hashIndex(hash(item), table);
        Node<T> p = table[hashIndex];
        while (p != null && !p.item.equals(item)) p = p.next;
        if (p == node) {
            table[hashIndex] = null;
        } else {
            while (p != null && p.next != node) p = p.next;
            if (p == null) {
                throw new IllegalStateException("Cannot remove item from hash table");
            }
        }

        Node<T> pred = node.prev;
        Node<T> successor = node.next;
        pred.next = successor;
        successor.prev = pred;

        --size;
        return item;
    }

    @SuppressWarnings("unchecked")
    public boolean remove(T item) {
        if (item == null) throw new NullPointerException();

        Node<T> node = hashLookup(item, table);
        if (node != null) {
            int hashIndex = hashIndex(hash(item), table);
            Node<T> p = table[hashIndex];
            while (p != null && !p.item.equals(item)) p = p.next;
            if (p == node) {
                table[hashIndex] = null;
            } else {
                while (p != null && p.next != node) p = p.next;
                if (p == null) {
                    throw new IllegalStateException("Cannot remove item from hash table");
                }
            }

            Node<T> pred = node.prev;
            Node<T> successor = node.next;
            pred.next = successor;
            successor.prev = pred;

            --size;
            return true;
        }
        return false;
    }

    public T removeFirst() {
        if (size == 0) throw new IllegalStateException();

        Node<T> node = header.next;
        T item = node.item;

        int hashIndex = hashIndex(hash(item), table);
        Node<T> p = table[hashIndex];
        while (p != null && !p.item.equals(item)) p = p.next;
        if (p == node) {
            table[hashIndex] = null;
        } else {
            while (p != null && p.next != node) p = p.next;
            if (p == null) {
                throw new IllegalStateException("Cannot remove item from hash table");
            }
        }

        Node<T> successor = node.next;
        header.next = successor;
        successor.prev = header;

        --size;
        return item;
    }

    public T removeLast() {
        if (size == 0) throw new IllegalStateException();

        Node<T> node = header.prev;
        T item = node.item;

        int hashIndex = hashIndex(hash(item), table);
        Node<T> p = table[hashIndex];
        while (p != null && !p.item.equals(item)) p = p.next;
        if (p == node) {
            table[hashIndex] = null;
        } else {
            while (p != null && p.next != node) p = p.next;
            if (p == null) {
                throw new IllegalStateException("Cannot remove item from hash table");
            }
        }

        Node<T> pred = node.prev;
        header.prev = pred;
        pred.next = header;

        --size;
        return item;
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        return hashLookup(item, table) != null;
    }

    public int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    public Object[] snapshot() {
        Object[] array = new Object[size];
        int i = 0;
        for (Node<T> node = header.next; node != null; node = node.next) {
            array[i++] = node.item;
        }
        return array;
    }
}
