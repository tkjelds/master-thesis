@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private Node<?>[] buckets;
    private Node<T> head, tail;
    private int size;
    private final Object lock = new Object();

    public HashedLinkedList() {
        this.buckets = new Node[DEFAULT_CAPACITY];
    }

    private static class Node<T> {
        T item;
        Node<T> next, prev;
        int hash;

        Node(T item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private int hash(Object obj) {
        return obj == null ? 0 : obj.hashCode();
    }

    private int indexFor(int hash) {
        return hash & (buckets.length - 1);
    }

    private Node<T> findNode(Object obj) {
        int hash = hash(obj);
        int index = indexFor(hash);
        Node<?> current = buckets[index];
        while (current != null) {
            if (current instanceof Node<T> && ((Node<T>) current).hash == hash && obj.equals(((Node<T>) current).item)) {
                return (Node<T>) current;
            }
            current = current.next;
        }
        return null;
    }

    @Override
    public synchronized boolean add(T item) {
        return addAt(size, item);
    }

    @Override
    public synchronized boolean addFirst(T item) {
        return addAt(0, item);
    }

    @Override
    public synchronized boolean insert(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index > size) throw new IndexOutOfBoundsException("Index: " + index);
        return addAt(index, item);
    }

    private boolean addAt(int index, T item) {
        int hash = hash(item);
        Node<T> newNode = new Node<>(item, hash);
        if (index == size) {
            if (tail != null) {
                tail.next = newNode;
                newNode.prev = tail;
                tail = newNode;
            } else {
                head = tail = newNode;
            }
        } else {
            Node<T> current = getNode(index);
            Node<T> prev = current.prev;
            newNode.next = current;
            newNode.prev = prev;
            if (prev != null) {
                prev.next = newNode;
            } else {
                head = newNode;
            }
            current.prev = newNode;
        }
        Node<T> bucketNode = findOrInsertHashEntry(item, hash);
        if (bucketNode != null) {
            bucketNode.item = item;
            return false;
        }
        size++;
        return true;
    }

    @Override
    public synchronized T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index: " + index);
        return getNode(index).item;
    }

    @Override
    public synchronized T set(int index, T item) {
        if (item == null) throw new NullPointerException();
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index: " + index);
        T oldItem = getNode(index).item;
        removeAt(index);
        addAt(index, item);
        return oldItem;
    }

    @Override
    public synchronized T removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index: " + index);
        Node<T> current = getNode(index);
        Node<T> prev = current.prev;
        Node<T> next = current.next;
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
        Node<T> bucketNode = findOrInsertHashEntry(current.item, hash(current.item));
        if (bucketNode != null) {
            bucketNode.item = null;
        }
        size--;
        return current.item;
    }

    @Override
    public synchronized boolean remove(T item) {
        Node<T> node = findNode(item);
        if (node != null) {
            removeAt(indexOf(node));
            return true;
        }
        return false;
    }

    @Override
    public synchronized T removeFirst() {
        if (head == null) throw new IllegalStateException("List is empty");
        return removeAt(0);
    }

    @Override
    public synchronized T removeLast() {
        if (tail == null) throw new IllegalStateException("List is empty");
        return removeAt(size - 1);
    }

    @Override
    public synchronized boolean contains(T item) {
        Node<T> node = findNode(item);
        return node != null;
    }

    @Override
    public synchronized int size() {
        return size;
    }

    private Node<T> findOrInsertHashEntry(T item, int hash) {
        int index = indexFor(hash);
        Node<?> current = buckets[index];
        while (current != null) {
            if (current instanceof Node<T> && ((Node<T>) current).hash == hash && item.equals(((Node<T>) current).item)) {
                return (Node<T>) current;
            }
            current = current.next;
        }
        Node<T> newNode = new Node<>(item, hash);
        if (buckets[index] != null) {
            Node<?> last = buckets[index];
            while (last.next != null) {
                last = last.next;
            }
            last.next = newNode;
        } else {
            buckets[index] = newNode;
        }
        return null;
    }

    private Node<T> getNode(int index) {
        int count = 0;
        Node<T> current = head;
        while (current != null && count < index) {
            current = current.next;
            count++;
        }
        return current;
    }

    private int indexOf(Node<T> node) {
        int count = 0;
        Node<T> current = head;
        while (current != null && current != node) {
            current = current.next;
            count++;
        }
        return current == null ? -1 : count;
    }

    @Override
    public synchronized Object[] snapshot() {
        Node<T> current = head;
        Object[] snapshot = new Object[size];
        int index = 0;
        while (current != null) {
            snapshot[index++] = current.item;
            current = current.next;
        }
        return snapshot;
    }
}
