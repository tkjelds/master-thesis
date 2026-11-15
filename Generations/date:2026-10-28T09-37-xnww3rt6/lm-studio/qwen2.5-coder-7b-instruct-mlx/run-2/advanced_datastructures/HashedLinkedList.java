@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;

    private Node head;
    private Node tail;
    private Entry[] table;
    private int size;

    public HashedLinkedList() {
        this.head = new Node(null);
        this.tail = new Node(null);
        head.next = tail;
        tail.prev = head;
        table = new Entry[INITIAL_CAPACITY];
    }

    private static class Node {
        T item;
        Node prev, next;

        Node(T item) {
            this.item = item;
        }
    }

    private static class Entry {
        T key;
        Node node;

        Entry(T key, Node node) {
            this.key = key;
            this.node = node;
        }
    }

    private int hash(T item) {
        return Math.abs(item.hashCode()) % table.length;
    }

    private void resize() {
        int newCapacity = table.length << 1;
        Entry[] newTable = new Entry[newCapacity];
        for (Entry entry : table) {
            while (entry != null) {
                int index = hash(entry.key);
                Entry nextEntry = entry.next;
                entry.next = newTable[index];
                newTable[index] = entry;
                entry = nextEntry;
            }
        }
        table = newTable;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        return insert(size, item);
    }

    public synchronized boolean addFirst(T item) {
        if (item == null) throw new NullPointerException();
        return insert(0, item);
    }

    public synchronized boolean insert(int index, T item) {
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        if (item == null) throw new NullPointerException();
        Node newNode = new Node(item);
        int hashIndex = hash(item);
        while (table[hashIndex] != null && !item.equals(table[hashIndex].key)) {
            hashIndex = (hashIndex + 1) % table.length;
        }
        if (table[hashIndex] != null) return false;

        Node prev = getNode(index);
        Node next = prev.next;
        newNode.prev = prev;
        newNode.next = next;
        prev.next = newNode;
        next.prev = newNode;

        table[hashIndex] = new Entry(item, newNode);
        size++;
        if (size >= table.length) resize();
        return true;
    }

    private Node getNode(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        Node node = head;
        for (int i = 0; i <= index; i++) {
            node = node.next;
        }
        return node;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return getNode(index).item;
    }

    public synchronized T set(int index, T item) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        if (item == null) throw new NullPointerException();

        Node node = getNode(index);
        T oldItem = node.item;

        int hashIndex = hash(oldItem);
        while (table[hashIndex] != null && !item.equals(table[hashIndex].key)) {
            hashIndex = (hashIndex + 1) % table.length;
        }
        if (table[hashIndex] == null || !oldItem.equals(item)) {
            throw new IllegalArgumentException("Cannot set item equal to one at another position");
        }

        table[hashIndex].key = item;
        node.item = item;

        return oldItem;
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();

        Node node = getNode(index);
        T item = node.item;

        int hashIndex = hash(item);
        while (table[hashIndex] != null && !item.equals(table[hashIndex].key)) {
            hashIndex = (hashIndex + 1) % table.length;
        }
        if (table[hashIndex] != null) {
            table[hashIndex] = null;
        }

        Node prev = node.prev;
        Node next = node.next;
        prev.next = next;
        next.prev = prev;

        size--;
        return item;
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();

        int hashIndex = hash(item);
        while (table[hashIndex] != null && !item.equals(table[hashIndex].key)) {
            hashIndex = (hashIndex + 1) % table.length;
        }

        if (table[hashIndex] == null) return false;

        Node node = table[hashIndex].node;
        T oldItem = node.item;
        int index = 0;
        Node current = head;
        while (current != null) {
            if (current == node) break;
            current = current.next;
            index++;
        }

        removeAt(index);

        table[hashIndex] = null;
        size--;

        return true;
    }

    public synchronized T removeFirst() {
        if (head.next == tail) throw new IllegalStateException("List is empty");

        Node node = head.next;
        T item = node.item;

        int hashIndex = hash(item);
        while (table[hashIndex] != null && !item.equals(table[hashIndex].key)) {
            hashIndex = (hashIndex + 1) % table.length;
        }
        if (table[hashIndex] != null) {
            table[hashIndex] = null;
        }

        Node nextNode = node.next;
        head.next = nextNode;
        nextNode.prev = head;

        size--;
        return item;
    }

    public synchronized T removeLast() {
        if (head.next == tail) throw new IllegalStateException("List is empty");

        Node node = tail.prev;
        T item = node.item;

        int hashIndex = hash(item);
        while (table[hashIndex] != null && !item.equals(table[hashIndex].key)) {
            hashIndex = (hashIndex + 1) % table.length;
        }
        if (table[hashIndex] != null) {
            table[hashIndex] = null;
        }

        Node prevNode = node.prev;
        tail.prev = prevNode;
        prevNode.next = tail;

        size--;
        return item;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();

        int hashIndex = hash(item);
        while (table[hashIndex] != null && !item.equals(table[hashIndex].key)) {
            hashIndex = (hashIndex + 1) % table.length;
        }
        return table[hashIndex] != null;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized Object[] snapshot() {
        Object[] snapshot = new Object[size];
        Node current = head.next;
        for (int i = 0; i < size; i++) {
            snapshot[i] = current.item;
            current = current.next;
        }
        return snapshot;
    }
}
