@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    private static final class Node<E> {
        private final E item;
        private Node<E> previous;
        private Node<E> next;

        private Node(E item) {
            this.item = item;
        }
    }

    public void addFirst(E item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (lock) {
            Node<E> node = new Node<>(item);
            node.next = head;
            if (head == null) {
                tail = node;
            } else {
                head.previous = node;
            }
            head = node;
            size++;
        }
    }

    public void addLast(E item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (lock) {
            Node<E> node = new Node<>(item);
            node.previous = tail;
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    public void add(E item) {
        addLast(item);
    }

    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> removed = head;
            head = removed.next;
            if (head == null) {
                tail = null;
            } else {
                head.previous = null;
            }
            size--;
            return removed.item;
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }

            Node<E> removed = tail;
            tail = removed.previous;
            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }
            size--;
            return removed.item;
        }
    }

    public E remove() {
        return removeFirst();
    }

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    public E peek() {
        return peekFirst();
    }

    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            Node<E> current;
            if (index < size / 2) {
                current = head;
                for (int i = 0; i < index; i++) {
                    current = current.next;
                }
            } else {
                current = tail;
                for (int i = size - 1; i > index; i--) {
                    current = current.previous;
                }
            }
            return current.item;
        }
    }

    public boolean contains(Object value) {
        synchronized (lock) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (current.item.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean remove(Object value) {
        synchronized (lock) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (current.item.equals(value)) {
                    unlink(current);
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
            Node<E> current = head;
            while (current != null) {
                Node<E> next = current.next;
                current.previous = null;
                current.next = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    private void unlink(Node<E> node) {
        if (node.previous == null) {
            head = node.next;
        } else {
            node.previous.next = node.next;
        }

        if (node.next == null) {
            tail = node.previous;
        } else {
            node.next.previous = node.previous;
        }

        node.previous = null;
        node.next = null;
        size--;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }
}
