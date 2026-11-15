@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public void addFirst(E value) {
        requireNonNull(value);
        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            if (head == null) {
                head = tail = node;
            } else {
                node.next = head;
                head.previous = node;
                head = node;
            }
            size++;
            lock.notifyAll();
        }
    }

    public void addLast(E value) {
        requireNonNull(value);
        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = tail = node;
            } else {
                node.previous = tail;
                tail.next = node;
                tail = node;
            }
            size++;
            lock.notifyAll();
        }
    }

    public boolean offerFirst(E value) {
        addFirst(value);
        return true;
    }

    public boolean offerLast(E value) {
        addLast(value);
        return true;
    }

    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkFirst();
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

    public E pollFirst() {
        synchronized (lock) {
            return head == null ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return tail == null ? null : unlinkLast();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (tail == null) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.value;
        }
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
            Node<E> node = head;
            while (node != null) {
                Node<E> next = node.next;
                node.previous = null;
                node.next = null;
                node = next;
            }
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
        node.next = null;
        node.previous = null;
        size--;
        return node.value;
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
        node.next = null;
        node.previous = null;
        size--;
        return node.value;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("Deque does not permit null elements");
        }
    }
}
