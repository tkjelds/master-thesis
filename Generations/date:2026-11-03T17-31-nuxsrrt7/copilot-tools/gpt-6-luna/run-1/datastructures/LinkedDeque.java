@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
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
    private int size;

    public void addFirst(E element) {
        requireElement(element);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<>(element);
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
            size++;
        }
    }

    public void addLast(E element) {
        requireElement(element);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<>(element);
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
            size++;
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

    public E removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("deque is empty");
            }
            return unlinkFirst();
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("deque is empty");
            }
            return unlinkLast();
        }
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

    public E getFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("deque is empty");
            }
            return first.item;
        }
    }

    public E getLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("deque is empty");
            }
            return last.item;
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

    public void push(E element) {
        addFirst(element);
    }

    public E pop() {
        return removeFirst();
    }

    public boolean add(E element) {
        addLast(element);
        return true;
    }

    public boolean offer(E element) {
        return offerLast(element);
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
        node.next = null;
        size--;
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
        size--;
        return node.item;
    }

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("deque size limit reached");
        }
    }

    private static void requireElement(Object element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
    }
}
