@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private Node<T> head;
    private Node<T> tail;
    private Object[] table;
    private int size;

    public HashedLinkedList() {
        this(INITIAL_CAPACITY);
    }

    public HashedLinkedList(int initialCapacity) {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        tail.prev = head;
        table = new Object[initialCapacity];
    }

    private int calculateHash(T item) {
        return Math.abs(item.hashCode() % table.length);
    }

    private void resize() {
        int newCapacity = table.length * 2;
        Object[] newTable = new Object[newCapacity];
        for (int i = 0; i < table.length; i++) {
            Node<T> node = (Node<T>) table[i];
            while (node != null) {
                int newIndex = calculateHash(node.item);
                Node<T> next = node.next;
                if (newTable[newIndex] == null) {
                    newTable[newIndex] = node;
                } else {
                    Node<T> last = (Node<T>) newTable[newIndex];
                    while (last.next != null) {
                        last = last.next;
                    }
                    last.next = node;
                }
                node.prev = null;
                node.next = null;
                node = next;
            }
        }
        table = newTable;
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException();
        return insert(size, item);
    }

    public boolean addFirst(T item) {
        if (item == null) throw new NullPointerException();
        return insert(0, item);
    }

    public boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        Node<T> newNode = new Node<>(item);
        synchronized (this) {
            if (contains(item)) return false;
            int hashIndex = calculateHash(item);
            if (table[hashIndex] == null) {
                table[hashIndex] = newNode;
            } else {
                Node<T> last = (Node<T>) table[hashIndex];
                while (last.next != null) {
                    last = last.next;
                }
                last.next = newNode;
            }

            if (index == 0) {
                head.next.prev = newNode;
                newNode.next = head.next;
                head.next = newNode;
            } else if (index == size) {
                tail.prev.next = newNode;
                newNode.prev = tail.prev;
                tail.prev = newNode;
            } else {
                Node<T> prevNode = getNthNode(index - 1);
                newNode.prev = prevNode;
                newNode.next = prevNode.next;
                prevNode.next.prev = newNode;
                prevNode.next = newNode;
            }
            size++;
            if (size > table.length * LOAD_FACTOR) resize();
        }
        return true;
    }

    public T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return getNthNode(index).item;
    }

    public T set(int index, T item) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        Node<T> node = getNthNode(index);
        if (item.equals(node.item)) return node.item;
        synchronized (this) {
            removeAt(index);
            insert(index, item);
        }
        return node.item;
    }

    public T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        Node<T> node = getNthNode(index);
        synchronized (this) {
            int hashIndex = calculateHash(node.item);
            Node<T> current = (Node<T>) table[hashIndex];
            while (current != null) {
                if (current == node) {
                    current.prev.next = current.next;
                    current.next.prev = current.prev;
                    if (current.prev == head) {
                        head.next = current.next;
                    } else if (current.next == tail) {
                        tail.prev = current.prev;
                    }
                    size--;
                    return node.item;
                }
                current = (Node<T>) current.next;
            }
        }
        return null;
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (this) {
            Node<T> node = getNthNode(0);
            while (node != null) {
                if (item.equals(node.item)) {
                    removeAt(0);
                    return true;
                }
                node = node.next;
            }
        }
        return false;
    }

    public T removeFirst() {
        if (isEmpty()) throw new IllegalStateException();
        return removeAt(0);
    }

    public T removeLast() {
        if (isEmpty()) throw new IllegalStateException();
        return removeAt(size - 1);
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        int hashIndex = calculateHash(item);
        Node<T> current = (Node<T>) table[hashIndex];
        while (current != null) {
            if (item.equals(current.item)) return true;
            current = (Node<T>) current.next;
        }
        return false;
    }

    public int size() {
        return size;
    }

    public Object[] snapshot() {
        synchronized (this) {
            Object[] result = new Object[size];
            Node<T> current = head.next;
            for (int i = 0; i < size && current != tail; i++) {
                result[i] = current.item;
                current = current.next;
            }
            return result;
        }
    }

    private Node<T> getNthNode(int index) {
        if (index < size / 2) {
            Node<T> current = head.next;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current;
        } else {
            Node<T> current = tail.prev;
            for (int i = size - 1; i > index; i--) {
                current = current.prev;
            }
            return current;
        }
    }

    private static class Node<T> {
        T item;
        Node<T> prev;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private boolean isEmpty() {
        return size == 0;
    }
}
