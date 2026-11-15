@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private T value;
        private Node<T> previous;
        private Node<T> next;

        private Node(T value) {
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
                tail = node;
            } else {
                node.previous = tail;
                tail.next = node;
                tail = node;
            }
            size++;
            return true;
        }
    }

    public void add(int index, E value) {
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }

            Node<E> node = new Node<E>(value);
            if (index == size) {
                if (tail == null) {
                    head = node;
                    tail = node;
                } else {
                    node.previous = tail;
                    tail.next = node;
                    tail = node;
                }
            } else {
                Node<E> next = nodeAt(index);
                node.next = next;
                node.previous = next.previous;
                if (next.previous == null) {
                    head = node;
                } else {
                    next.previous.next = node;
                }
                next.previous = node;
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
        synchronized (lock) {
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            Node<E> node = nodeAt(index);
            unlink(node);
            return node.value;
        }
    }

    public boolean remove(Object value) {
        synchronized (lock) {
            Node<E> node = head;
            while (node != null) {
                if (value == null ? node.value == null : value.equals(node.value)) {
                    unlink(node);
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public boolean contains(Object value) {
        synchronized (lock) {
            Node<E> node = head;
            while (node != null) {
                if (value == null ? node.value == null : value.equals(node.value)) {
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public int indexOf(Object value) {
        synchronized (lock) {
            Node<E> node = head;
            int index = 0;
            while (node != null) {
                if (value == null ? node.value == null : value.equals(node.value)) {
                    return index;
                }
                node = node.next;
                index++;
            }
            return -1;
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
            Node<E> node = head;
            while (node != null) {
                Node<E> next = node.next;
                node.value = null;
                node.previous = null;
                node.next = null;
                node = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node<E> node = head;
            for (int i = 0; i < size; i++) {
                result[i] = node.value;
                node = node.next;
            }
            return result;
        }
    }

    private Node<E> nodeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }

        if (index < size / 2) {
            Node<E> node = head;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }

        Node<E> node = tail;
        for (int i = size - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
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

        node.value = null;
        node.previous = null;
        node.next = null;
        size--;
    }
}
