@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public void add(E value) {
        requireNonNull(value);
        synchronized (lock) {
            Node<E> node = new Node<>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    public void addFirst(E value) {
        requireNonNull(value);
        synchronized (lock) {
            Node<E> node = new Node<>(value);
            node.next = head;
            head = node;
            if (tail == null) {
                tail = node;
            }
            size++;
        }
    }

    public void add(int index, E value) {
        requireNonNull(value);
        synchronized (lock) {
            checkPositionIndex(index);
            Node<E> node = new Node<>(value);
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
            return nodeAt(index).value;
        }
    }

    public E set(int index, E value) {
        requireNonNull(value);
        synchronized (lock) {
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.next = node.next;
            return replaceValue(node, value, previous);
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("List is empty");
            }
            E value = head.value;
            head = head.next;
            size--;
            if (head == null) {
                tail = null;
            }
            return value;
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("List is empty");
            }
            if (head == tail) {
                E value = head.value;
                head = null;
                tail = null;
                size = 0;
                return value;
            }
            Node<E> previous = head;
            while (previous.next != tail) {
                previous = previous.next;
            }
            E value = tail.value;
            previous.next = null;
            tail = previous;
            size--;
            return value;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            if (index == 0) {
                E value = head.value;
                head = head.next;
                size--;
                if (head == null) {
                    tail = null;
                }
                return value;
            }
            Node<E> previous = nodeAt(index - 1);
            Node<E> removed = previous.next;
            previous.next = removed.next;
            if (removed == tail) {
                tail = previous;
            }
            size--;
            return removed.value;
        }
    }

    public boolean remove(Object value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;
            while (current != null) {
                if (value.equals(current.value)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    if (current == tail) {
                        tail = previous;
                    }
                    size--;
                    return true;
                }
                previous = current;
                current = current.next;
            }
            return false;
        }
    }

    public boolean contains(Object value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (value.equals(current.value)) {
                    return true;
                }
            }
            return false;
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
        checkElementIndex(index);
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

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("Null values are not supported");
        }
    }

    private E replaceValue(Node<E> node, E value, E previous) {
        Node<E> replacement = new Node<>(value);
        replacement.next = node.next;
        if (node == head) {
            head = replacement;
        } else {
            Node<E> current = head;
            while (current.next != node) {
                current = current.next;
            }
            current.next = replacement;
        }
        if (node == tail) {
            tail = replacement;
        }
        return previous;
    }
}
