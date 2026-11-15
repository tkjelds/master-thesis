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

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    public void addFirst(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
            size++;
            lock.notifyAll();
        }
    }

    public void addLast(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
            size++;
            lock.notifyAll();
        }
    }

    public E removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    public E removeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            return size == 0 ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return size == 0 ? null : unlinkLast();
        }
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
        Node<E> node = first;
        Node<E> next = node.next;
        first = next;
        if (next == null) {
            last = null;
        } else {
            next.previous = null;
        }
        size--;
        node.next = null;
        return node.value;
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
        size--;
        node.previous = null;
        return node.value;
    }
}
