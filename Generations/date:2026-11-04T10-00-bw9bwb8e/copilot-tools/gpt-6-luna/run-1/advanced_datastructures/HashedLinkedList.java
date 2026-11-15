@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private Object[] buckets = new Object[16];
    private Node first;
    private Node last;
    private int size;

    private final class Node {
        T item;
        Node previous;
        Node next;
        Node bucketNext;
        int hash;

        Node(T item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return insertInternal(size, item);
        }
    }

    public boolean addFirst(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return insertInternal(0, item);
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (monitor) {
            checkPositionIndex(index);
            return insertInternal(index, item);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (monitor) {
            checkElementIndex(index);
            Node node = nodeAt(index);
            int hash = spreadHash(item.hashCode());
            Node existing = find(item, hash);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("Item already exists in the list");
            }

            T previous = node.item;
            if (existing == node) {
                node.item = item;
                return previous;
            }

            removeFromBuckets(node);
            node.item = item;
            node.hash = hash;
            addToBuckets(node);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            Node node = nodeAt(index);
            T item = node.item;
            unlinkNode(node);
            return item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = spreadHash(item.hashCode());
            Node node = find(item, hash);
            if (node == null) {
                return false;
            }
            unlinkNode(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (monitor) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            T item = first.item;
            unlinkNode(first);
            return item;
        }
    }

    public T removeLast() {
        synchronized (monitor) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            T item = last.item;
            unlinkNode(last);
            return item;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = spreadHash(item.hashCode());
            return find(item, hash) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[size];
            Node node = first;
            for (int i = 0; i < result.length; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private boolean insertInternal(int index, T item) {
        int hash = spreadHash(item.hashCode());
        if (find(item, hash) != null) {
            return false;
        }

        Node node = new Node(item, hash);
        ensureCapacity();
        Node next = index == size ? null : nodeAt(index);
        Node previous = next == null ? last : next.previous;

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

        addToBuckets(node);
        size++;
        return true;
    }

    private Node find(T item, int hash) {
        Node node = bucketHead(hash & (buckets.length - 1));
        while (node != null) {
            if (node.hash == hash && node.item.equals(item)) {
                return node;
            }
            node = node.bucketNext;
        }
        return null;
    }

    private void addToBuckets(Node node) {
        int index = node.hash & (buckets.length - 1);
        node.bucketNext = bucketHead(index);
        buckets[index] = node;
    }

    private void removeFromBuckets(Node target) {
        int index = target.hash & (buckets.length - 1);
        Node previous = null;
        Node node = bucketHead(index);
        while (node != null) {
            if (node == target) {
                if (previous == null) {
                    buckets[index] = node.bucketNext;
                } else {
                    previous.bucketNext = node.bucketNext;
                }
                node.bucketNext = null;
                return;
            }
            previous = node;
            node = node.bucketNext;
        }
    }

    private void ensureCapacity() {
        int threshold = buckets.length - (buckets.length >>> 2);
        if (size < threshold || buckets.length >= (1 << 30)) {
            return;
        }

        Object[] expanded = new Object[buckets.length << 1];
        for (int i = 0; i < buckets.length; i++) {
            Node node = bucketHead(i);
            while (node != null) {
                Node next = node.bucketNext;
                int index = node.hash & (expanded.length - 1);
                node.bucketNext = (Node) expanded[index];
                expanded[index] = node;
                node = next;
            }
        }
        buckets = expanded;
    }

    private Node nodeAt(int index) {
        if (index < (size >>> 1)) {
            Node node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }

        Node node = last;
        for (int i = size - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
    }

    private void unlinkNode(Node node) {
        removeFromBuckets(node);

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

        size--;
        node.item = null;
        node.previous = null;
        node.next = null;
        node.bucketNext = null;
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

    private void requireNonNull(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private Node bucketHead(int index) {
        return (Node) buckets[index];
    }
}
