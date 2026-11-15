@HashedLinkedList.ThreadSafe
public class HashedLinkedList<T> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final HashIndex<T> index = new HashIndex<T>();
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = hashOf(item);
            if (index.find(item, hash) != null) {
                return false;
            }

            Node<T> node = new Node<T>(item, hash);
            index.ensureCapacityForInsert();
            node.prev = tail;
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            index.addNode(node);
            size++;
            return true;
        }
    }

    public boolean addFirst(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int hash = hashOf(item);
            if (index.find(item, hash) != null) {
                return false;
            }

            Node<T> node = new Node<T>(item, hash);
            index.ensureCapacityForInsert();
            node.next = head;
            if (head == null) {
                tail = node;
            } else {
                head.prev = node;
            }
            head = node;
            index.addNode(node);
            size++;
            return true;
        }
    }

    public boolean insert(int index, T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
            }

            int hash = hashOf(item);
            if (this.index.find(item, hash) != null) {
                return false;
            }

            Node<T> node = new Node<T>(item, hash);
            this.index.ensureCapacityForInsert();

            if (index == size) {
                node.prev = tail;
                if (tail == null) {
                    head = node;
                } else {
                    tail.next = node;
                }
                tail = node;
            } else {
                linkBefore(node, nodeAt(index));
            }

            this.index.addNode(node);
            size++;
            return true;
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> node = nodeAt(index);
            int hash = hashOf(item);
            Node<T> existing = this.index.find(item, hash);
            if (existing != null && existing != node) {
                throw new IllegalArgumentException("item already exists");
            }

            T previous = node.item;
            this.index.removeNode(node);
            node.item = item;
            node.hash = hash;
            this.index.addNode(node);
            return previous;
        }
    }

    public T removeAt(int index) {
        synchronized (monitor) {
            Node<T> node = nodeAt(index);
            T item = node.item;
            unlink(node);
            return item;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> node = this.index.find(item, hashOf(item));
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        }
    }

    public T removeFirst() {
        synchronized (monitor) {
            if (head == null) {
                throw new IllegalStateException("list is empty");
            }
            T item = head.item;
            unlink(head);
            return item;
        }
    }

    public T removeLast() {
        synchronized (monitor) {
            if (tail == null) {
                throw new IllegalStateException("list is empty");
            }
            T item = tail.item;
            unlink(tail);
            return item;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return index.find(item, hashOf(item)) != null;
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
            Node<T> node = head;
            for (int i = 0; i < size; i++) {
                result[i] = node.item;
                node = node.next;
            }
            return result;
        }
    }

    private Node<T> nodeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }

        if (index < size / 2) {
            Node<T> node = head;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }

        Node<T> node = tail;
        for (int i = size - 1; i > index; i--) {
            node = node.prev;
        }
        return node;
    }

    private void linkBefore(Node<T> node, Node<T> successor) {
        Node<T> predecessor = successor.prev;
        node.prev = predecessor;
        node.next = successor;
        successor.prev = node;
        if (predecessor == null) {
            head = node;
        } else {
            predecessor.next = node;
        }
    }

    private void unlink(Node<T> node) {
        index.removeNode(node);

        if (node.prev == null) {
            head = node.next;
        } else {
            node.prev.next = node.next;
        }
        if (node.next == null) {
            tail = node.prev;
        } else {
            node.next.prev = node.prev;
        }

        node.prev = null;
        node.next = null;
        node.bucketNext = null;
        size--;
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int hashOf(Object item) {
        int hash = item.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static final class Node<E> {
        private E item;
        private int hash;
        private Node<E> prev;
        private Node<E> next;
        private Node<E> bucketNext;

        private Node(E item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private static final class HashIndex<E> {
        private Node<E>[] buckets;
        private int entries;
        private int threshold;

        @SuppressWarnings("unchecked")
        private HashIndex() {
            buckets = (Node<E>[]) new Node[16];
            threshold = 12;
        }

        private Node<E> find(E item, int hash) {
            Node<E> node = buckets[hash & (buckets.length - 1)];
            while (node != null) {
                if (node.hash == hash && item.equals(node.item)) {
                    return node;
                }
                node = node.bucketNext;
            }
            return null;
        }

        private void addNode(Node<E> node) {
            int bucket = node.hash & (buckets.length - 1);
            node.bucketNext = buckets[bucket];
            buckets[bucket] = node;
            entries++;
        }

        private void removeNode(Node<E> target) {
            int bucket = target.hash & (buckets.length - 1);
            Node<E> node = buckets[bucket];
            Node<E> previous = null;

            while (node != null) {
                if (node == target) {
                    if (previous == null) {
                        buckets[bucket] = node.bucketNext;
                    } else {
                        previous.bucketNext = node.bucketNext;
                    }
                    node.bucketNext = null;
                    entries--;
                    return;
                }
                previous = node;
                node = node.bucketNext;
            }
        }

        private void ensureCapacityForInsert() {
            if (entries < threshold || buckets.length >= (1 << 30)) {
                return;
            }

            resize(buckets.length << 1);
        }

        @SuppressWarnings("unchecked")
        private void resize(int capacity) {
            Node<E>[] newBuckets = (Node<E>[]) new Node[capacity];
            for (int i = 0; i < buckets.length; i++) {
                Node<E> node = buckets[i];
                while (node != null) {
                    Node<E> next = node.bucketNext;
                    int bucket = node.hash & (capacity - 1);
                    node.bucketNext = newBuckets[bucket];
                    newBuckets[bucket] = node;
                    node = next;
                }
            }
            buckets = newBuckets;
            threshold = capacity - (capacity >>> 2);
        }
    }
}
