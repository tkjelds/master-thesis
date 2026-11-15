@HashedArrayList.ThreadSafe
public class HashedArrayList<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_TABLE_CAPACITY = 1 << 30;

    private static final class Node {
        Object item;
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
    private Node[] order = new Node[INITIAL_CAPACITY];
    private Node[] buckets = new Node[INITIAL_CAPACITY];
    private int size;

    // Operations do not wait for capacity or items. They may wait to acquire
    // the monitor, and allocation failure is reported by the runtime.
    public boolean add(T item) {
        synchronized (lock) {
            requireItem(item);
            return insertInternal(size, item);
        }
    }

    public boolean insert(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkInsertIndex(index);
            return insertInternal(index, item);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return valueOf(order[index]);
        }
    }

    public T set(int index, T item) {
        synchronized (lock) {
            requireItem(item);
            checkElementIndex(index);

            int hash = hashOf(item);
            Node existing = find(item, hash);
            Node old = order[index];

            if (existing != null && existing != old) {
                throw new IllegalArgumentException("Item already exists at another index");
            }

            T previous = valueOf(old);
            if (existing == old) {
                // Equal items have equal hashes, so the existing bucket is valid.
                old.item = item;
            } else {
                Node replacement = new Node(item, hash, index);
                unlink(old);
                link(replacement);
                order[index] = replacement;
            }
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return removeAtInternal(index);
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            requireItem(item);
            Node node = find(item, hashOf(item));
            if (node == null) {
                return false;
            }
            removeAtInternal(node.index);
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            requireItem(item);
            return find(item, hashOf(item)) != null;
        }
    }

    public int indexOf(T item) {
        synchronized (lock) {
            requireItem(item);
            Node node = find(item, hashOf(item));
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
                result[i] = order[i].item;
            }
            return result;
        }
    }

    private boolean insertInternal(int index, T item) {
        int hash = hashOf(item);
        if (find(item, hash) != null) {
            return false;
        }
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List is too large");
        }

        Node node = new Node(item, hash, index);
        int newSize = size + 1;
        ensureOrderCapacity(newSize);
        ensureTableCapacity(newSize);

        for (int i = size; i > index; i--) {
            Node shifted = order[i - 1];
            order[i] = shifted;
            shifted.index = i;
        }
        order[index] = node;
        link(node);
        size = newSize;
        return true;
    }

    private T removeAtInternal(int index) {
        Node removed = order[index];
        T previous = valueOf(removed);
        unlink(removed);

        int last = size - 1;
        for (int i = index; i < last; i++) {
            Node shifted = order[i + 1];
            order[i] = shifted;
            shifted.index = i;
        }
        order[last] = null;
        size = last;
        return previous;
    }

    private void ensureOrderCapacity(int required) {
        if (required <= order.length) {
            return;
        }

        long doubled = (long) order.length * 2;
        int capacity = doubled > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) doubled;
        if (capacity < required) {
            capacity = required;
        }

        Node[] grown = new Node[capacity];
        for (int i = 0; i < size; i++) {
            grown[i] = order[i];
        }
        order = grown;
    }

    private void ensureTableCapacity(int required) {
        int capacity = buckets.length;
        int threshold = capacity - (capacity >>> 2);
        if (required <= threshold || capacity == MAX_TABLE_CAPACITY) {
            return;
        }

        Node[] grown = new Node[capacity * 2];
        for (int i = 0; i < capacity; i++) {
            Node node = buckets[i];
            while (node != null) {
                Node next = node.next;
                int bucket = node.hash & (grown.length - 1);
                node.next = grown[bucket];
                grown[bucket] = node;
                node = next;
            }
        }
        buckets = grown;
    }

    private Node find(Object item, int hash) {
        Node node = buckets[hash & (buckets.length - 1)];
        while (node != null) {
            if (node.hash == hash && item.equals(node.item)) {
                return node;
            }
            node = node.next;
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
        Node node = buckets[bucket];

        while (node != target) {
            previous = node;
            node = node.next;
        }
        if (previous == null) {
            buckets[bucket] = node.next;
        } else {
            previous.next = node.next;
        }
        node.next = null;
    }

    private static int hashOf(Object item) {
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private void checkInsertIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    @SuppressWarnings("unchecked")
    private T valueOf(Node node) {
        return (T) node.item;
    }
}
