@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E value;
        private Node<E> previous;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    public void addFirst(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            linkFirst(new Node<E>(element));
        }
    }

    public void addLast(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            linkLast(new Node<E>(element));
        }
    }

    public boolean offerFirst(E element) {
        addFirst(element);
        return true;
    }

    public boolean offerLast(E element) {
        addLast(element);
        return true;
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.value;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            return first == null ? null : unlink(first);
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return last == null ? null : unlink(last);
        }
    }

    public E getFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("deque is empty");
            }
            return first.value;
        }
    }

    public E getLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("deque is empty");
            }
            return last.value;
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("deque is empty");
            }
            return unlink(first);
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("deque is empty");
            }
            return unlink(last);
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
            Node<E> current = first;
            while (current != null) {
                Node<E> next = current.next;
                current.previous = null;
                current.next = null;
                current = next;
            }
            first = null;
            last = null;
            size = 0;
        }
    }

    private void linkFirst(Node<E> node) {
        Node<E> oldFirst = first;
        node.next = oldFirst;
        if (oldFirst == null) {
            last = node;
        } else {
            oldFirst.previous = node;
        }
        first = node;
        size++;
    }

    private void linkLast(Node<E> node) {
        Node<E> oldLast = last;
        node.previous = oldLast;
        if (oldLast == null) {
            first = node;
        } else {
            oldLast.next = node;
        }
        last = node;
        size++;
    }

    private E unlink(Node<E> node) {
        Node<E> previous = node.previous;
        Node<E> next = node.next;

        if (previous == null) {
            first = next;
        } else {
            previous.next = next;
        }

        if (next == null) {
            last = previous;
        } else {
            next.previous = previous;
        }

        node.previous = null;
        node.next = null;
        size--;
        return node.value;
    }
}
