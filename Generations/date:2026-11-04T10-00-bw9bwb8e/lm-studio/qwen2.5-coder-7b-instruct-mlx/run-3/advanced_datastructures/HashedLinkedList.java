import java.util.NoSuchElementException;

@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private Node[] table;
    private Node head;
    private Node tail;
    private int size;
    private int mask;

    public HashedLinkedList() {
        table = new Node[INITIAL_CAPACITY];
        head = null;
        tail = null;
        size = 0;
        mask = INITIAL_CAPACITY - 1;
    }

    private static class Node<T> {
        T item;
        Node<T> next;
        Node<T> prev;

        Node(T item) {
            this.item = item;
            next = null;
            prev = null;
        }
    }

    private int hash(T item) {
        return item == null ? 0 : item.hashCode() & mask;
    }

    private Node<T> getFirstNode(int hash) {
        return table[hash];
    }

    private void setFirstNode(int hash, Node<T> node) {
        table[hash] = node;
    }

    private boolean isInList(Node<T> node) {
        return head == null ? false : (head.prev != node && tail.next != node);
    }

    private void addNode(Node<T> newNode, Node<T> prev, Node<T> next) {
        if (prev != null) {
            prev.next = newNode;
        } else {
            head = newNode;
        }
        if (next != null) {
            next.prev = newNode;
        } else {
            tail = newNode;
        }
    }

    private void removeNode(Node<T> node) {
        Node<T> prev = node.prev;
        Node<T> next = node.next;
        if (prev != null) {
            prev.next = next;
        } else {
            head = next;
        }
        if (next != null) {
            next.prev = prev;
        } else {
            tail = prev;
        }
    }

    public synchronized boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        int hash = hash(item);
        Node<T> newNode = new Node<>(item);
        if (contains(item)) {
            return false;
        }
        newNode.next = getFirstNode(hash);
        setFirstNode(hash, newNode);
        addNode(newNode, null, head);
        size++;
        return true;
    }

    public synchronized boolean addFirst(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        int hash = hash(item);
        Node<T> newNode = new Node<>(item);
        if (contains(item)) {
            return false;
        }
        newNode.prev = null;
        newNode.next = head;
        setFirstNode(hash, newNode);
        if (head != null) {
            head.prev = newNode;
        } else {
            tail = newNode;
        }
        head = newNode;
        size++;
        return true;
    }

    public synchronized boolean insert(int index, T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException();
        }
        if (contains(item)) {
            return false;
        }
        Node<T> newNode = new Node<>(item);
        if (index == 0) {
            return addFirst(item);
        }
        Node<T> prev = getNode(index - 1);
        newNode.prev = prev;
        newNode.next = prev.next;
        if (prev.next != null) {
            prev.next.prev = newNode;
        } else {
            tail = newNode;
        }
        setFirstNode(hash(item), newNode);
        prev.next = newNode;
        size++;
        return true;
    }

    private Node<T> getNode(int index) {
        if (index < size / 2) {
            Node<T> node = head;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        } else {
            Node<T> node = tail;
            for (int i = size - 1; i > index; i--) {
                node = node.prev;
            }
            return node;
        }
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return getNode(index).item;
    }

    public synchronized T set(int index, T item) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
        if (item == null) {
            throw new NullPointerException();
        }
        Node<T> node = getNode(index);
        if (item.equals(node.item)) {
            return item;
        }
        if (contains(item)) {
            throw new IllegalArgumentException();
        }
        int hash = hash(node.item);
        setFirstNode(hash, null);
        removeNode(node);
        Node<T> newNode = new Node<>(item);
        setFirstNode(hash, newNode);
        addNode(newNode, null, head);
        return node.item;
    }

    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
        Node<T> node = getNode(index);
        int hash = hash(node.item);
        setFirstNode(hash, null);
        removeNode(node);
        size--;
        return node.item;
    }

    public synchronized boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        int hash = hash(item);
        Node<T> node = getFirstNode(hash);
        while (node != null) {
            if (item.equals(node.item)) {
                int nodeHash = hash(node.item);
                setFirstNode(nodeHash, null);
                removeNode(node);
                size--;
                return true;
            }
            node = node.next;
        }
        return false;
    }

    public synchronized T removeFirst() {
        if (head == null) {
            throw new IllegalStateException();
        }
        return removeAt(0);
    }

    public synchronized T removeLast() {
        if (tail == null) {
            throw new IllegalStateException();
        }
        return removeAt(size - 1);
    }

    public synchronized boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        int hash = hash(item);
        Node<T> node = getFirstNode(hash);
        while (node != null) {
            if (item.equals(node.item)) {
                return true;
            }
            node = node.next;
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    public synchronized Object[] snapshot() {
        Object[] result = new Object[size];
        Node<T> node = head;
        for (int i = 0; i < size; i++) {
            result[i] = node.item;
            node = node.next;
        }
        return (Object[]) result.clone();
    }
}
