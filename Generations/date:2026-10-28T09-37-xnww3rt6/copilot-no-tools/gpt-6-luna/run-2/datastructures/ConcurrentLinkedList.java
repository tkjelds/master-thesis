@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
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

    public boolean add(E item) {
        return addLast(item);
    }

    public boolean addFirst(E item) {
        requireNonNull(item);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(item);
            Node<E> currentHead = head;

            node.next = currentHead;
            head = node;
            if (currentHead == null) {
                tail = node;
            } else {
                currentHead.previous = node;
            }

            size++;
            lock.notifyAll();
            return true;
        }
    }

    public boolean addLast(E item) {
        requireNonNull(item);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(item);
            Node<E> currentTail = tail;

            node.previous = currentTail;
            tail = node;
            if (currentTail == null) {
                head = node;
            } else {
                currentTail.next = node;
            }

            size++;
            lock.notifyAll();
            return true;
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            return head == null ? null : unlinkFirst();
        }
    }

    public E removeLast() {
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

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
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
            head = null;
            tail = null;
            size = 0;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node<E> current = head;
            for (int i = 0; i < size; i++) {
                result[i] = current.item;
                current = current.next;
            }
            return result;
        }
    }

    private E unlinkFirst() {
        Node<E> first = head;
        Node<E> next = first.next;

        head = next;
        first.next = null;
        if (next == null) {
            tail = null;
        } else {
            next.previous = null;
        }

        size--;
        return first.item;
    }

    private E unlinkLast() {
        Node<E> last = tail;
        Node<E> previous = last.previous;

        tail = previous;
        last.previous = null;
        if (previous == null) {
            head = null;
        } else {
            previous.next = null;
        }

        size--;
        return last.item;
    }

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is too large");
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not supported");
        }
    }
}
