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
    private Node<T>[] buckets = newTable(INITIAL_CAPACITY);
    private int threshold = INITIAL_CAPACITY - INITIAL_CAPACITY / 4;
    private Node<T> first;
    private Node<T> last;
    private int count;

    @SuppressWarnings("unchecked")
    private static <E> Node<E>[] newTable(int capacity) {
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

    private Node<T> find(T item, int hash) {
        Node<T> node = buckets[hash & (buckets.length - 1)];
        while (node != null) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
            node = node.bucketNext;
        }
        return null;
    }

    private void ensureInsertCapacity() {
        if (count == Integer.MAX_VALUE) {
            throw new IllegalStateException("list is full");
        }
        if (count < threshold) {
            return;
        }

        int oldCapacity = buckets.length;
        if (oldCapacity == MAX_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }

        int newCapacity = oldCapacity << 1;
        Node<T>[] newBuckets = newTable(newCapacity);
        for (int i = 0; i < oldCapacity; i++) {
            Node<T> node = buckets[i];
            while (node != null) {
                Node<T> next = node.bucketNext;
                int index = node.hash & (newCapacity - 1);
                node.bucketNext = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }
        buckets = newBuckets;
        threshold = newCapacity == MAX_CAPACITY
                ? Integer.MAX_VALUE
                : newCapacity - newCapacity / 4;
    }

    private void addBetween(T item, int hash, Node<T> previous, Node<T> next) {
        Node<T> node = new Node<T>(item, hash);
        ensureInsertCapacity();

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

        int index = hash & (buckets.length - 1);
        node.bucketNext = buckets[index];
        buckets[index] = node;
        count++;
    }

    private void removeFromBucket(Node<T> node) {
        int index = node.hash & (buckets.length - 1);
        Node<T> current = buckets[index];
        Node<T> previous = null;
        while (current != node) {
            previous = current;
            current = current.bucketNext;
        }
        if (previous == null) {
            buckets[index] = current.bucketNext;
        } else {
            previous.bucketNext = current.bucketNext;
        }
        node.bucketNext = null;
    }

    private T unlink(Node<T> node) {
        T item = node.item;
        removeFromBucket(node);

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

        node.previous = null;
        node.next = null;
        node.item = null;
        count--;
        return item;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > count) {
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
        } else {
            Node<T> node = last;
            for (int i = count - 1; i > index; i--) {
                node = node.previous;
            }
            return node;
        }
    }

    public boolean add(T item) {
        synchronized (lock) {
            requireItem(item);
            int hash = hash(item);
            if (find(item, hash) != null) {
                return false;
            }
            addBetween(item, hash, last, null);
            return true;
        }
    }

    public boolean addFirst(T item) {
        synchronized (lock) {
            requireItem(item);
            int hash = hash(item);
            if (find(item, hash) != null) {
                return false;
            }
            addBetween(item, hash, null, first);
            return true;
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkPositionIndex(index);
            int hash = hash(item);
            if (find(item, hash) != null) {
                return false;
            }
            Node<T> next = index == count ? null : nodeAt(index);
            Node<T> previous = next == null ? last : next.previous;
            addBetween(item, hash, previous, next);
            return true;
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
                throw new IllegalArgumentException("item already exists");
            }

            T oldItem = target.item;
            if (existing == target) {
                target.item = item;
            } else {
                removeFromBucket(target);
                target.item = item;
                target.hash = hash;
                int bucket = hash & (buckets.length - 1);
                target.bucketNext = buckets[bucket];
                buckets[bucket] = target;
            }
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
                throw new IllegalStateException("list is empty");
            }
            return unlink(first);
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("list is empty");
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
