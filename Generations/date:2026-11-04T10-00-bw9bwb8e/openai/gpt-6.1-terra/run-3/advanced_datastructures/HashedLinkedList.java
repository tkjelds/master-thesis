@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private static final class Node<E> {
        E item;
        int hash;
        Node<E> prev;
        Node<E> next;
        Node<E> bucketNext;

        Node(E item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();
    private Node<T>[] table;
    private Node<T> first;
    private Node<T> last;
    private int size;
    private int threshold;

    @SuppressWarnings("unchecked")
    public HashedLinkedList() {
        table = (Node<T>[]) new Node<?>[INITIAL_CAPACITY];
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    public boolean add(T item) {
        synchronized (lock) {
            requireItem(item);
            return insertNode(item, null);
        }
    }

    public boolean addFirst(T item) {
        synchronized (lock) {
            requireItem(item);
            return insertNode(item, first);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkInsertIndex(index);
            Node<T> before = index == size ? null : nodeAt(index);
            return insertNode(item, before);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkElementIndex(index);
            Node<T> target = nodeAt(index);
            int hash = hash(item);
            Node<T> existing = find(item, hash);
            if (existing != null && existing != target) {
                throw new IllegalArgumentException("Item already exists");
            }

            T previous = target.item;
            removeFromBucket(target);
            target.item = item;
            target.hash = hash;
            addToBucket(target);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return unlink(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireItem(item);
            Node<T> node = find(item, hash(item));
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlink(first);
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlink(last);
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireItem(item);
            return find(item, hash(item)) != null;
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
            Node<T> node = first;
            for (int i = 0; i < result.length; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private void requireItem(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private int hash(T item) {
        int h = item.hashCode();
        return h ^ (h >>> 16);
    }

    private Node<T> find(T item, int hash) {
        Node<T> node = table[hash & (table.length - 1)];
        while (node != null) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
            node = node.bucketNext;
        }
        return null;
    }

    private boolean insertNode(T item, Node<T> before) {
        int hash = hash(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is too large");
        }

        Node<T> node = new Node<T>(item, hash);
        if (size >= threshold) {
            grow();
        }

        if (before == null) {
            node.prev = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
        } else {
            node.next = before;
            node.prev = before.prev;
            if (before.prev == null) {
                first = node;
            } else {
                before.prev.next = node;
            }
            before.prev = node;
        }
        addToBucket(node);
        size++;
        return true;
    }

    private void addToBucket(Node<T> node) {
        int index = node.hash & (table.length - 1);
        node.bucketNext = table[index];
        table[index] = node;
    }

    private void removeFromBucket(Node<T> target) {
        int index = target.hash & (table.length - 1);
        Node<T> node = table[index];
        Node<T> previous = null;
        while (node != target) {
            previous = node;
            node = node.bucketNext;
        }
        if (previous == null) {
            table[index] = node.bucketNext;
        } else {
            previous.bucketNext = node.bucketNext;
        }
        node.bucketNext = null;
    }

    private T unlink(Node<T> node) {
        removeFromBucket(node);

        if (node.prev == null) {
            first = node.next;
        } else {
            node.prev.next = node.next;
        }
        if (node.next == null) {
            last = node.prev;
        } else {
            node.next.prev = node.prev;
        }

        node.prev = null;
        node.next = null;
        size--;
        return node.item;
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        int oldCapacity = table.length;
        if (oldCapacity == MAX_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }

        int newCapacity = oldCapacity << 1;
        Node<T>[] newTable = (Node<T>[]) new Node<?>[newCapacity];
        Node<T> node = first;
        while (node != null) {
            int index = node.hash & (newCapacity - 1);
            node.bucketNext = newTable[index];
            newTable[index] = node;
            node = node.next;
        }
        table = newTable;
        threshold = newCapacity - (newCapacity >>> 2);
    }

    private Node<T> nodeAt(int index) {
        if (index < (size >>> 1)) {
            Node<T> node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        } else {
            Node<T> node = last;
            for (int i = size - 1; i > index; i--) {
                node = node.prev;
            }
            return node;
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
    }

    private void checkInsertIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
    }
}
