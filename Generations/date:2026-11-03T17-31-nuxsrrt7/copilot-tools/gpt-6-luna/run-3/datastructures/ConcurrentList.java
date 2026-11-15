@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E value;
        private Node<E> next;

        private Node(E value, Node<E> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object monitor = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public void add(E value) {
        synchronized (monitor) {
            ensureCapacity();
            Node<E> node = new Node<>(value, null);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    public void add(int index, E value) {
        synchronized (monitor) {
            checkPositionIndex(index);
            ensureCapacity();

            if (index == size) {
                Node<E> node = new Node<>(value, null);
                if (tail == null) {
                    head = node;
                } else {
                    tail.next = node;
                }
                tail = node;
            } else if (index == 0) {
                head = new Node<>(value, head);
            } else {
                Node<E> previous = nodeAt(index - 1);
                previous.next = new Node<>(value, previous.next);
            }
            size++;
        }
    }

    public E get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    public E set(int index, E value) {
        synchronized (monitor) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    public E remove(int index) {
        synchronized (monitor) {
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
                if (removed == tail) {
                    tail = previous;
                }
            }

            size--;
            E value = removed.value;
            removed.value = null;
            removed.next = null;
            return value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    public Object[] toArray() {
        synchronized (monitor) {
            Object[] result = new Object[size];
            Node<E> current = head;
            for (int i = 0; i < size; i++) {
                result[i] = current.value;
                current = current.next;
            }
            return result;
        }
    }

    private Node<E> nodeAt(int index) {
        Node<E> current = head;
        for (int i = 0; i < index; i++) {
            current = current.next;
        }
        return current;
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

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is too large");
        }
    }
}
