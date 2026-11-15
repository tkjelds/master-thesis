public final class LinkedDeque<E> {
    private static final class Node<E> {
        final E item;
        Node<E> previous;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int count;

    public LinkedDeque() {
    }

    public void addFirst(E item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<E> node = new Node<>(item);
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
            count++;
            lock.notifyAll();
        }
    }

    public void addLast(E item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<E> node = new Node<>(item);
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
            count++;
            lock.notifyAll();
        }
    }

    public boolean offerFirst(E item) {
        addFirst(item);
        return true;
    }

    public boolean offerLast(E item) {
        addLast(item);
        return true;
    }

    public E pollFirst() {
        synchronized (lock) {
            return first == null ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return last == null ? null : unlinkLast();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (first == null) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (last == null) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.item;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            first = null;
            last = null;
            count = 0;
            lock.notifyAll();
        }
    }

    private E unlinkFirst() {
        Node<E> node = first;
        Node<E> next = node.next;
        first = next;
        if (next == null) {
            last = null;
        } else {
            next.previous = null;
        }
        node.next = null;
        count--;
        return node.item;
    }

    private E unlinkLast() {
        Node<E> node = last;
        Node<E> previous = node.previous;
        last = previous;
        if (previous == null) {
            first = null;
        } else {
            previous.next = null;
        }
        node.previous = null;
        count--;
        return node.item;
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("Deque does not accept null elements");
        }
    }
}
