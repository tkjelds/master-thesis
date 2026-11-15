@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
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
    private Node<E> first;
    private Node<E> last;
    private long count;

    public void addFirst(E element) {
        requireElement(element);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(element);
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

    public void addLast(E element) {
        requireElement(element);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(element);
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

    public boolean offerFirst(E element) {
        addFirst(element);
        return true;
    }

    public boolean offerLast(E element) {
        addLast(element);
        return true;
    }

    public E pollFirst() {
        synchronized (lock) {
            return unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return unlinkLast();
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

    public long size() {
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
        count--;
        return node.item;
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
        count--;
        return node.item;
    }

    private void ensureCapacity() {
        if (count == Long.MAX_VALUE) {
            throw new IllegalStateException("Deque size limit reached");
        }
    }

    private static void requireElement(Object element) {
        if (element == null) {
            throw new NullPointerException("Deque does not allow null elements");
        }
    }
}
