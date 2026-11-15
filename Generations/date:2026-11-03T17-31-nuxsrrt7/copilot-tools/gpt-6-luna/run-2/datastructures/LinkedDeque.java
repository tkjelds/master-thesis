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

    public LinkedDeque() {
    }

    public void addFirst(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (this) {
            Node<E> node = new Node<>(value);
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
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (this) {
            Node<E> node = new Node<>(value);
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

    public E takeFirst() throws InterruptedException {
        synchronized (this) {
            while (first == null) {
                wait();
            }
            return removeFirstNode().value;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (this) {
            while (last == null) {
                wait();
            }
            return removeLastNode().value;
        }
    }

    public E pollFirst() {
        synchronized (this) {
            return first == null ? null : removeFirstNode().value;
        }
    }

    public E pollLast() {
        synchronized (this) {
            return last == null ? null : removeLastNode().value;
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
            first = null;
            last = null;
            size = 0;
        }
    }

    private Node<E> removeFirstNode() {
        Node<E> node = first;
        Node<E> next = node.next;

        first = next;
        if (next == null) {
            last = null;
        } else {
            next.previous = null;
        }

        node.next = null;
        size--;
        return node;
    }

    private Node<E> removeLastNode() {
        Node<E> node = last;
        Node<E> previous = node.previous;

        last = previous;
        if (previous == null) {
            first = null;
        } else {
            previous.next = null;
        }

        node.previous = null;
        size--;
        return node;
    }
}
