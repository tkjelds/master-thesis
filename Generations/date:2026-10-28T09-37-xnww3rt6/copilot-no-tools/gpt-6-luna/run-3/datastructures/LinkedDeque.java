@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> previous;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private Node<E> first;
    private Node<E> last;
    private int size;

    public void addFirst(E value) {
        requireNonNull(value);
        synchronized (this) {
            Node<E> node = new Node<E>(value);
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
            size++;
            notifyAll();
        }
    }

    public void addLast(E value) {
        requireNonNull(value);
        synchronized (this) {
            Node<E> node = new Node<E>(value);
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
            size++;
            notifyAll();
        }
    }

    public E pollFirst() {
        synchronized (this) {
            return unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (this) {
            return unlinkLast();
        }
    }

    public E removeFirst() {
        synchronized (this) {
            if (first == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkFirst();
        }
    }

    public E removeLast() {
        synchronized (this) {
            if (last == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkLast();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (this) {
            while (first == null) {
                wait();
            }
            return unlinkFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (this) {
            while (last == null) {
                wait();
            }
            return unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (this) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (this) {
            return last == null ? null : last.value;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            Node<E> node = first;
            while (node != null) {
                Node<E> next = node.next;
                node.previous = null;
                node.next = null;
                node = next;
            }
            first = null;
            last = null;
            size = 0;
        }
    }

    private E unlinkFirst() {
        if (first == null) {
            return null;
        }
        Node<E> node = first;
        first = node.next;
        if (first == null) {
            last = null;
        } else {
            first.previous = null;
        }
        node.next = null;
        size--;
        return node.value;
    }

    private E unlinkLast() {
        if (last == null) {
            return null;
        }
        Node<E> node = last;
        last = node.previous;
        if (last == null) {
            first = null;
        } else {
            last.next = null;
        }
        node.previous = null;
        size--;
        return node.value;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Null elements are not supported");
        }
    }
}
