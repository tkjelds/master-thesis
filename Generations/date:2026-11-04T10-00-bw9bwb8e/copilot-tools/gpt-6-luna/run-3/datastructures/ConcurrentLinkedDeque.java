@ConcurrentLinkedDeque.ThreadSafe
public final class ConcurrentLinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E item;
        Node<E> previous;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public void addFirst(E item) {
        offerFirst(item);
    }

    public boolean offerFirst(E item) {
        requireNonNull(item);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<>(item);
            node.next = head;
            if (head == null) {
                tail = node;
            } else {
                head.previous = node;
            }
            head = node;
            size++;
            return true;
        }
    }

    public void addLast(E item) {
        offerLast(item);
    }

    public boolean offerLast(E item) {
        requireNonNull(item);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<>(item);
            node.previous = tail;
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            return true;
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkFirst();
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            return head == null ? null : unlinkFirst();
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkLast();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return tail == null ? null : unlinkLast();
        }
    }

    public E getFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return head.item;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public E getLast() {
        synchronized (lock) {
            if (tail == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return tail.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    public boolean add(E item) {
        addLast(item);
        return true;
    }

    public boolean offer(E item) {
        return offerLast(item);
    }

    public E remove() {
        return removeFirst();
    }

    public E poll() {
        return pollFirst();
    }

    public E element() {
        return getFirst();
    }

    public E peek() {
        return peekFirst();
    }

    public void push(E item) {
        addFirst(item);
    }

    public E pop() {
        return removeFirst();
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    private E unlinkFirst() {
        Node<E> node = head;
        Node<E> next = node.next;
        head = next;
        if (next == null) {
            tail = null;
        } else {
            next.previous = null;
        }
        size--;
        node.item = null;
        node.next = null;
        return node.item == null ? null : node.item;
    }

    private E unlinkLast() {
        Node<E> node = tail;
        Node<E> previous = node.previous;
        tail = previous;
        if (previous == null) {
            head = null;
        } else {
            previous.next = null;
        }
        size--;
        E item = node.item;
        node.item = null;
        node.previous = null;
        return item;
    }

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Deque has reached its maximum size");
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("Deque does not support null elements");
        }
    }
}
