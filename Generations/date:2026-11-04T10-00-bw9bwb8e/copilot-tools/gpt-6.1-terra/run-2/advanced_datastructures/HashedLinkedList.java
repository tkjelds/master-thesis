@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private static final class Node<E> {
        E item;
        int hash;
        Node<E> previous;
        Node<E> next;
        Node<E> bucketNext;

        Node(E item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();
    private Node<T>[] buckets = newBuckets(INITIAL_CAPACITY);
    private int threshold = INITIAL_CAPACITY - INITIAL_CAPACITY / 4;
    private Node<T> first;
    private Node<T> last;
    private int count;

    @SuppressWarnings("unchecked")
    private static <E> Node<E>[] newBuckets(int capacity) {
        return (Node<E>[]) new Node<?>[capacity];
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int hash(Object item) {
        int h = item.hashCode();
        return h ^ (h >>> 16);
    }

    private Node<T> find(Object item, int hash) {
        Node<T> node = buckets[hash & (buckets.length - 1)];
        while (node != null) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
            node = node.bucketNext;
        }
        return null;
    }

    private void growIfNeeded() {
        if (count < threshold) {
            return;
        }
        int oldCapacity = buckets.length;
        if (oldCapacity == MAX_CAPACITY) {
            threshold = 2147483647;
            return;
        }
        Node<T>[] newTable = newBuckets(oldCapacity << 1);
        for (int i = 0; i < oldCapacity; i++) {
            Node<T> node = buckets[i];
            while (node != null) {
                Node<T> next = node.bucketNext;
                int bucket = node.hash & (newTable.length - 1);
                node.bucketNext = newTable[bucket];
                newTable[bucket] = node;
                node = next;
            }
        }
        buckets = newTable;
        threshold = newTable.length - newTable.length / 4;
    }

    private void addToTable(Node<T> node) {
        int bucket = node.hash & (buckets.length - 1);
        node.bucketNext = buckets[bucket];
        buckets[bucket] = node;
    }

    private void removeFromTable(Node<T> node) {
        int bucket = node.hash & (buckets.length - 1);
        Node<T> previous = null;
        Node<T> current = buckets[bucket];
        while (current != null && current != node) {
            previous = current;
            current = current.bucketNext;
        }
        if (current == null) {
            throw new IllegalStateException("Inconsistent hash table");
        }
        if (previous == null) {
            buckets[bucket] = current.bucketNext;
        } else {
            previous.bucketNext = current.bucketNext;
        }
        current.bucketNext = null;
    }

    private boolean insertBetween(T item, Node<T> previous, Node<T> next) {
        int itemHash = hash(item);
        if (find(item, itemHash) != null) {
            return false;
        }
        if (count == 2147483647) {
            throw new IllegalStateException("List is too large");
        }
        Node<T> node = new Node<T>(item, itemHash);
        growIfNeeded();
        node.previous = previous;
        node.next = next;
        if (previous == null) {
            first = node;
        } else {
            previous.next = node;
        }
        if (next == null) {
            last = node;
        } else {
            next.previous = node;
        }
        addToTable(node);
        count++;
        return true;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private Node<T> nodeAt(int index) {
        if (index < count / 2) {
            Node<T> node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }
        Node<T> node = last;
        for (int i = count - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
    }

    private T unlink(Node<T> node) {
        removeFromTable(node);
        if (node.previous == null) {
            first = node.next;
        } else {
            node.previous.next = node.next;
        }
        if (node.next == null) {
            last = node.previous;
        } else {
            node.next.previous = node.previous;
        }
        count--;
        node.previous = null;
        node.next = null;
        return node.item;
    }

    public boolean add(T item) {
        requireItem(item);
        synchronized (lock) {
            return insertBetween(item, last, null);
        }
    }

    public boolean addFirst(T item) {
        requireItem(item);
        synchronized (lock) {
            return insertBetween(item, null, first);
        }
    }

    public boolean insert(int index, T item) {
        requireItem(item);
        synchronized (lock) {
            if (index < 0 || index > count) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            if (index == count) {
                return insertBetween(item, last, null);
            }
            Node<T> next = nodeAt(index);
            return insertBetween(item, next.previous, next);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        requireItem(item);
        synchronized (lock) {
            checkElementIndex(index);
            Node<T> node = nodeAt(index);
            int itemHash = hash(item);
            Node<T> existing = find(item, itemHash);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("Duplicate item");
            }
            T oldItem = node.item;
            removeFromTable(node);
            node.item = item;
            node.hash = itemHash;
            addToTable(node);
            return oldItem;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return unlink(nodeAt(index));
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (lock) {
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
        requireItem(item);
        synchronized (lock) {
            return find(item, hash(item)) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[count];
            Node<T> node = first;
            for (int i = 0; i < count; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }
}
