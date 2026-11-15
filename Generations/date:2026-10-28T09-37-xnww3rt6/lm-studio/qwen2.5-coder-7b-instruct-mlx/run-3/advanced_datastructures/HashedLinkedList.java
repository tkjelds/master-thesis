@HashedLinkedList.ThreadSafe
class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> next, prev;

        Node(T item) {
            this.item = item;
            next = prev = null;
        }
    }

    private Node<T> head, tail;
    private Object[] table;
    private int size;
    private final Object monitor = new Object();

    public HashedLinkedList() {
        head = tail = null;
        table = new Object[16];
        size = 0;
    }

    public boolean add(T item) {
        synchronized (monitor) {
            if (item == null) throw new NullPointerException();
            if (contains(item)) return false;
            addLast(item);
            return true;
        }
    }

    public boolean addFirst(T item) {
        synchronized (monitor) {
            if (item == null) throw new NullPointerException();
            if (contains(item)) return false;
            addFirst(item);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        synchronized (monitor) {
            if (item == null) throw new NullPointerException();
            if (index < 0 || index > size) throw new IndexOutOfBoundsException();
            if (contains(item)) return false;
            insert(index, item);
            return true;
        }
    }

    public T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return getNode(index).item;
    }

    public T set(int index, T item) {
        synchronized (monitor) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            Node<T> node = getNode(index);
            T oldItem = node.item;
            if (item.equals(oldItem)) return item;
            removeAt(index);
            insert(index, item);
            return oldItem;
        }
    }

    public T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        Node<T> node = getNode(index);
        remove(node);
        return node.item;
    }

    public boolean remove(T item) {
        synchronized (monitor) {
            Node<T> node = getNode(item);
            if (node == null) return false;
            remove(node);
            return true;
        }
    }

    public T removeFirst() {
        if (isEmpty()) throw new IllegalStateException();
        Node<T> first = head;
        remove(first);
        return first.item;
    }

    public T removeLast() {
        if (isEmpty()) throw new IllegalStateException();
        Node<T> last = tail;
        remove(last);
        return last.item;
    }

    public boolean contains(T item) {
        synchronized (monitor) {
            return getNode(item) != null;
        }
    }

    public int size() {
        return size;
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] snapshot = new Object[size];
            Node<T> current = head;
            for (int i = 0; i < size; i++) {
                snapshot[i] = current.item;
                current = current.next;
            }
            return snapshot;
        }
    }

    private void addFirst(T item) {
        Node<T> newNode = new Node<>(item);
        if (head == null) {
            head = tail = newNode;
        } else {
            newNode.next = head;
            head.prev = newNode;
            head = newNode;
        }
        insertIntoTable(item, newNode);
    }

    private void addLast(T item) {
        Node<T> newNode = new Node<>(item);
        if (tail == null) {
            head = tail = newNode;
        } else {
            newNode.prev = tail;
            tail.next = newNode;
            tail = newNode;
        }
        insertIntoTable(item, newNode);
    }

    private void insert(int index, T item) {
        Node<T> newNode = new Node<>(item);
        if (index == 0) {
            addFirst(item);
        } else if (index == size) {
            addLast(item);
        } else {
            Node<T> prev = getNode(index - 1);
            newNode.next = prev.next;
            newNode.prev = prev;
            prev.next.prev = newNode;
            prev.next = newNode;
            insertIntoTable(item, newNode);
        }
    }

    private Node<T> getNode(int index) {
        Node<T> current = head;
        for (int i = 0; i < index; i++) {
            current = current.next;
        }
        return current;
    }

    private Node<T> getNode(T item) {
        int hash = item.hashCode();
        for (Node<T> node = (Node<T>) table[hash & table.length - 1]; node != null; node = node.next) {
            if (item.equals(node.item)) return node;
        }
        return null;
    }

    private void insertIntoTable(T item, Node<T> node) {
        int hash = item.hashCode();
        Node<T> head = (Node<T>) table[hash & table.length - 1];
        if (head == null) {
            table[hash & table.length - 1] = node;
        } else {
            while (head.next != null) head = head.next;
            head.next = node;
        }
    }

    private void remove(Node<T> node) {
        if (node == null) return;
        Node<T> prev = node.prev;
        Node<T> next = node.next;
        if (prev == null) {
            head = next;
        } else {
            prev.next = next;
        }
        if (next == null) {
            tail = prev;
        } else {
            next.prev = prev;
        }
        removeFromTable(node.item);
        size--;
    }

    private void removeFromTable(T item) {
        int hash = item.hashCode();
        Node<T> prev = null;
        for (Node<T> node = (Node<T>) table[hash & table.length - 1]; node != null; prev = node, node = node.next) {
            if (item.equals(node.item)) {
                if (prev == null) {
                    table[hash & table.length - 1] = node.next;
                } else {
                    prev.next = node.next;
                }
                return;
            }
        }
    }

    private boolean isEmpty() {
        return size == 0;
    }
}
