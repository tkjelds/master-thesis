@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 8;
    private Node<T>[] buckets;
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public HashedLinkedList() {
        this.buckets = new Node[INITIAL_CAPACITY];
    }

    private static class Node<T> {
        T item;
        Node<T> next;
        Node<T> prev;

        Node(T item) {
            this.item = item;
        }
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException("Null items are not allowed");
        return add(size, item);
    }

    public synchronized boolean addFirst(T item) {
        if (item == null) throw new NullPointerException("Null items are not allowed");
        return add(0, item);
    }

    public synchronized boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException("Null items are not allowed");
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        return add(index, item);
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return findNode(index).item;
    }

    public synchronized T set(int index, T item) {
        if (item == null) throw new NullPointerException("Null items are not allowed");
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        Node<T> node = findNode(index);
        T oldItem = node.item;
        if (!item.equals(oldItem)) throw new IllegalArgumentException();
        node.item = item;
        return oldItem;
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        Node<T> node = findNode(index);
        unlink(node);
        return node.item;
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException("Null items are not allowed");
        Node<T> node = findNode(item);
        if (node != null) {
            unlink(node);
            return true;
        }
        return false;
    }

    public synchronized T removeFirst() {
        if (head == null) throw new IllegalStateException("List is empty");
        Node<T> node = head;
        unlink(node);
        return node.item;
    }

    public synchronized T removeLast() {
        if (tail == null) throw new IllegalStateException("List is empty");
        Node<T> node = tail;
        unlink(node);
        return node.item;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException("Null items are not allowed");
        return findNode(item) != null;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized Object[] snapshot() {
        Object[] snapshot = new Object[size];
        Node<T> current = head;
        for (int i = 0; i < size; i++) {
            snapshot[i] = current.item;
            current = current.next;
        }
        return snapshot;
    }

    private synchronized Node<T> findNode(int index) {
        int bucketIndex = (findHash(findNode(index).item) & (buckets.length - 1));
        Node<T> current = buckets[bucketIndex];
        while (current != null) {
            if (index == 0) break;
            current = current.next;
            index--;
        }
        return current;
    }

    private synchronized Node<T> findNode(T item) {
        int bucketIndex = (findHash(item) & (buckets.length - 1));
        Node<T> current = buckets[bucketIndex];
        while (current != null) {
            if (item.equals(current.item)) break;
            current = current.next;
        }
        return current;
    }

    private synchronized int findHash(Object item) {
        return item.hashCode();
    }

    private synchronized Node<T> add(int index, T item) {
        int bucketIndex = (findHash(item) & (buckets.length - 1));
        Node<T> newNode = new Node<>(item);
        if (index == 0) {
            newNode.next = head;
            newNode.prev = null;
            if (head != null) head.prev = newNode;
            head = newNode;
            if (tail == null) tail = newNode;
        } else if (index == size) {
            newNode.next = null;
            newNode.prev = tail;
            if (tail != null) tail.next = newNode;
            tail = newNode;
            if (head == null) head = newNode;
        } else {
            Node<T> current = findNode(index);
            newNode.next = current;
            newNode.prev = current.prev;
            if (current.prev != null) current.prev.next = newNode;
            current.prev = newNode;
        }
        int bucketIndexNew = (findHash(item) & (buckets.length - 1));
        if (bucketIndex != bucketIndexNew) {
            newNode.next = null;
            newNode.prev = null;
        }
        buckets[bucketIndexNew] = newNode;
        size++;
        return newNode;
    }

    private synchronized void unlink(Node<T> node) {
        if (node.prev != null) node.prev.next = node.next;
        else head = node.next;
        if (node.next != null) node.next.prev = node.prev;
        else tail = node.prev;
        int bucketIndex = (findHash(node.item) & (buckets.length - 1));
        Node<T> current = buckets[bucketIndex];
        while (current != null) {
            if (node == current) break;
            current = current.next;
        }
        if (current != null) {
            current = buckets[bucketIndex];
            while (current != null) {
                if (node == current) break;
                current = current.next;
            }
        }
        size--;
    }
}
