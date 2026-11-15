@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
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

    public ConcurrentList() {
    }

    public void add(E element) {
        synchronized (lock) {
            Node<E> node = new Node<E>(element);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            checkPositionIndex(index);
            Node<E> node = new Node<E>(element);
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

    public E set(int index, E element) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node = new Node<E>(element);
            if (index == 0) {
                node.next = head.next;
                head = node;
            } else {
                Node<E> previousNode = nodeAt(index - 1);
                node.next = previousNode.next.next;
                previousNode.next = node;
            }
            if (index == size - 1) {
                tail = node;
            }
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
                if (removed == tail) {
                    tail = previous;
                }
            }
            size--;
            return removed.value;
        }
    }

    public boolean remove(Object element) {
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;
            while (current != null) {
                if (equal(current.value, element)) {
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

    public boolean contains(Object element) {
        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                if (equal(current.value, element)) {
                    return true;
                }
                current = current.next;
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

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node<E> current = head;
            for (int i = 0; i < result.length; i++) {
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
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Size: " + size);
        }
    }

    private boolean equal(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
