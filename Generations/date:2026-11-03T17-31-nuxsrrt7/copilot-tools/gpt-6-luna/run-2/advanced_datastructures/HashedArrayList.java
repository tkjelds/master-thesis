@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final class Node {
        final Object item;
        final int hash;
        int index;
        Node next;

        Node(Object item, int hash, int index) {
            this.item = item;
            this.hash = hash;
            this.index = index;
        }
    }

    private final Object lock = new Object();
    private Node[] elements = new Node[8];
    private Node[] buckets = new Node[16];
    private int size;

    public boolean add(T item) {
        synchronized (lock) {
            requireNonNull(item);
            return insertAt(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            checkPositionIndex(index);
            requireNonNull(item);
            return insertAt(index, item);
        }
    }

    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return (T) elements[index].item;
        }
    }

    @SuppressWarnings("unchecked")
    public T set(int index, T item) {
        synchronized (lock) {
            checkElementIndex(index);
            requireNonNull(item);

            Node previous = elements[index];
            int hash = spreadHash(item.hashCode());
            Node existing = find(item, hash);
            if (existing != null && existing != previous) {
                throw new IllegalArgumentException("Item already exists");
            }

            Node replacement = new Node(item, hash, index);
            unlink(previous);
            elements[index] = replacement;
            link(replacement);
            return (T) previous.item;
        }
    }

    @SuppressWarnings("unchecked")
    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            Node removed = elements[index];
            removeNode(removed);
            return (T) removed.item;
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireNonNull(item);
            Node node = find(item, spreadHash(item.hashCode()));
            if (node == null) {
                return false;
            }
            removeNode(node);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireNonNull(item);
            return find(item, spreadHash(item.hashCode())) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (lock) {
            requireNonNull(item);
            Node node = find(item, spreadHash(item.hashCode()));
            return node == null ? -1 : node.index;
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
            for (int i = 0; i < size; i++) {
                result[i] = elements[i].item;
            }
            return result;
        }
    }

    private boolean insertAt(int index, Object item) {
        int hash = spreadHash(item.hashCode());
        if (find(item, hash) != null) {
            return false;
        }

        Node node = new Node(item, hash, index);
        ensureElementCapacity(size + 1);
        ensureBucketCapacity(size + 1);

        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
            elements[i].index = i;
        }
        elements[index] = node;
        link(node);
        size++;
        return true;
    }

    private void removeNode(Node node) {
        unlink(node);

        for (int i = node.index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
            elements[i].index = i;
        }
        elements[--size] = null;
    }

    private Node find(Object item, int hash) {
        for (Node node = buckets[hash & (buckets.length - 1)]; node != null; node = node.next) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
        }
        return null;
    }

    private void link(Node node) {
        int bucket = node.hash & (buckets.length - 1);
        node.next = buckets[bucket];
        buckets[bucket] = node;
    }

    private void unlink(Node target) {
        int bucket = target.hash & (buckets.length - 1);
        Node previous = null;
        Node current = buckets[bucket];

        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    buckets[bucket] = current.next;
                } else {
                    previous.next = current.next;
                }
                current.next = null;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private void ensureElementCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int capacity = elements.length;
        while (capacity < required) {
            capacity *= 2;
        }

        Node[] replacement = new Node[capacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = elements[i];
        }
        elements = replacement;
    }

    private void ensureBucketCapacity(int required) {
        if (required <= buckets.length - (buckets.length >>> 2)) {
            return;
        }

        Node[] replacement = new Node[buckets.length * 2];
        for (int i = 0; i < size; i++) {
            Node node = elements[i];
            int bucket = node.hash & (replacement.length - 1);
            node.next = replacement[bucket];
            replacement[bucket] = node;
        }
        buckets = replacement;
    }

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }
}
