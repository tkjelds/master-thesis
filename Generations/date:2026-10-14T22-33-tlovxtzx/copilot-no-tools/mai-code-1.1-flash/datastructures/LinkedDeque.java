@LinkedDeque.ThreadSafe
public class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final int UNBOUNDED_CAPACITY = Integer.MAX_VALUE;

    private final int capacity;
    private int count;
    private Node<E> head;
    private Node<E> tail;

    private static final class Node<E> {
        final E item;
        Node<E> prev;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    public static final class NoSuchElementException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NoSuchElementException() {
            super();
        }

        public NoSuchElementException(String message) {
            super(message);
        }
    }

    public LinkedDeque() {
        this(UNBOUNDED_CAPACITY);
    }

    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void putFirst(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (this) {
            while (count >= capacity) {
                wait();
            }
            insertFirstInternal(e);
            notifyAll();
        }
    }

    public void putLast(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (this) {
            while (count >= capacity) {
                wait();
            }
            insertLastInternal(e);
            notifyAll();
        }
    }

    public boolean offerFirst(E e) {
        checkNotNull(e);
        synchronized (this) {
            if (count >= capacity) {
                return false;
            }
            insertFirstInternal(e);
            notifyAll();
            return true;
        }
    }

    public boolean offerLast(E e) {
        checkNotNull(e);
        synchronized (this) {
            if (count >= capacity) {
                return false;
            }
            insertLastInternal(e);
            notifyAll();
            return true;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            E value = removeFirstInternal();
            notifyAll();
            return value;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            E value = removeLastInternal();
            notifyAll();
            return value;
        }
    }

    public E pollFirst() {
        synchronized (this) {
            if (count == 0) {
                return null;
            }
            E value = removeFirstInternal();
            notifyAll();
            return value;
        }
    }

    public E pollLast() {
        synchronized (this) {
            if (count == 0) {
                return null;
            }
            E value = removeLastInternal();
            notifyAll();
            return value;
        }
    }

    public E peekFirst() {
        synchronized (this) {
            return count == 0 ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (this) {
            return count == 0 ? null : tail.item;
        }
    }

    public E peek() {
        return peekFirst();
    }

    public E poll() {
        return pollFirst();
    }

    public boolean add(E e) {
        try {
            putLast(e);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while adding to deque", ie);
        }
    }

    public boolean offer(E e) {
        return offerLast(e);
    }

    public void addFirst(E e) {
        try {
            putFirst(e);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while adding to front", ie);
        }
    }

    public void addLast(E e) {
        try {
            putLast(e);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while adding to back", ie);
        }
    }

    public E remove() {
        try {
            return takeFirst();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while removing head", ie);
        }
    }

    public E removeFirst() {
        E value = pollFirst();
        if (value == null) {
            throw new NoSuchElementException("deque is empty");
        }
        return value;
    }

    public E removeLast() {
        E value = pollLast();
        if (value == null) {
            throw new NoSuchElementException("deque is empty");
        }
        return value;
    }

    public E element() {
        E value = peekFirst();
        if (value == null) {
            throw new NoSuchElementException("deque is empty");
        }
        return value;
    }

    public E getFirst() {
        E value = peekFirst();
        if (value == null) {
            throw new NoSuchElementException("deque is empty");
        }
        return value;
    }

    public E getLast() {
        E value = peekLast();
        if (value == null) {
            throw new NoSuchElementException("deque is empty");
        }
        return value;
    }

    public void push(E e) {
        addFirst(e);
    }

    public E pop() {
        return removeFirst();
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public int remainingCapacity() {
        synchronized (this) {
            if (capacity == UNBOUNDED_CAPACITY) {
                return UNBOUNDED_CAPACITY;
            }
            return capacity - count;
        }
    }

    public void clear() {
        synchronized (this) {
            head = null;
            tail = null;
            count = 0;
            notifyAll();
        }
    }

    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (this) {
            Node<E> current = head;
            while (current != null) {
                if (o.equals(current.item)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (this) {
            Node<E> current = head;
            while (current != null) {
                if (o.equals(current.item)) {
                    unlink(current);
                    notifyAll();
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public boolean removeFirstOccurrence(Object o) {
        return remove(o);
    }

    public boolean removeLastOccurrence(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (this) {
            Node<E> current = tail;
            while (current != null) {
                if (o.equals(current.item)) {
                    unlink(current);
                    notifyAll();
                    return true;
                }
                current = current.prev;
            }
            return false;
        }
    }

    private void checkNotNull(E value) {
        if (value == null) {
            throw new NullPointerException("null elements are not allowed");
        }
    }

    private void insertFirstInternal(E e) {
        Node<E> node = new Node<E>(e);
        if (count == 0) {
            head = node;
            tail = node;
        } else {
            node.next = head;
            head.prev = node;
            head = node;
        }
        count++;
    }

    private void insertLastInternal(E e) {
        Node<E> node = new Node<E>(e);
        if (count == 0) {
            head = node;
            tail = node;
        } else {
            node.prev = tail;
            tail.next = node;
            tail = node;
        }
        count++;
    }

    private E removeFirstInternal() {
        Node<E> first = head;
        E value = first.item;
        if (count == 1) {
            head = null;
            tail = null;
        } else {
            head = first.next;
            head.prev = null;
            first.next = null;
        }
        count--;
        return value;
    }

    private E removeLastInternal() {
        Node<E> last = tail;
        E value = last.item;
        if (count == 1) {
            head = null;
            tail = null;
        } else {
            tail = last.prev;
            tail.next = null;
            last.prev = null;
        }
        count--;
        return value;
    }

    private void unlink(Node<E> node) {
        Node<E> prev = node.prev;
        Node<E> next = node.next;

        if (prev == null) {
            head = next;
        } else {
            prev.next = next;
            node.prev = null;
        }

        if (next == null) {
            tail = prev;
        } else {
            next.prev = prev;
            node.next = null;
        }

        if (head == null) {
            tail = null;
        }
        if (tail == null) {
            head = null;
        }
        count--;
    }
}
