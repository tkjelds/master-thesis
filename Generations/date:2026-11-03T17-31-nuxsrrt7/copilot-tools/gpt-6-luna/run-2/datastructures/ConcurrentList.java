@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public boolean add(E value) {
        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            return true;
        }
    }

    public void add(int index, E value) {
        synchronized (lock) {
            checkPositionIndex(index);

            Node<E> node = new Node<E>(value);
            if (index == 0) {
                node.next = head;
                head = node;
                if (tail == null) {
                    tail = node;
                }
            } else {
                Node<E> previous = nodeAt(index - 1);
                node.next = previous.next;
                previous.next = node;
                if (node.next == null) {
                    tail = node;
                }
            }
            size++;
        }
    }

    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    public E set(int index, E value) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);

            Node<E> removed;
            if (index == 0) {
                removed = head;
                head = head.next;
                if (head == null) {
                    tail = null;
                }
            } else {
                Node<E> previous = nodeAt(index - 1);
                removed = previous.next;
                previous.next = removed.next;
                if (previous.next == null) {
                    tail = previous;
                }
            }

            size--;
            removed.next = null;
            return removed.value;
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

    private Node<E> nodeAt(int index) {
        Node<E> node = head;
        for (int i = 0; i < index; i++) {
            node = node.next;
        }
        return node;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }
}
