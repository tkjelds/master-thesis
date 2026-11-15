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

    private final Object mutex = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public boolean add(E value) {
        synchronized (mutex) {
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

    public void addFirst(E value) {
        synchronized (mutex) {
            Node<E> node = new Node<E>(value);
            node.next = head;
            head = node;
            if (tail == null) {
                tail = node;
            }
            size++;
        }
    }

    public void addLast(E value) {
        add(value);
    }

    public void insert(int index, E value) {
        synchronized (mutex) {
            checkPositionIndex(index);
            if (index == 0) {
                Node<E> node = new Node<E>(value);
                node.next = head;
                head = node;
                if (tail == null) {
                    tail = node;
                }
            } else {
                Node<E> previous = nodeAt(index - 1);
                Node<E> node = new Node<E>(value);
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
        synchronized (mutex) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    public E first() {
        synchronized (mutex) {
            checkElementIndex(0);
            return head.value;
        }
    }

    public E last() {
        synchronized (mutex) {
            checkElementIndex(size - 1);
            return tail.value;
        }
    }

    public E set(int index, E value) {
        synchronized (mutex) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previousValue = node.value;
            Node<E> replacement = new Node<E>(value);
            replacement.next = node.next;

            if (index == 0) {
                head = replacement;
            } else {
                nodeAt(index - 1).next = replacement;
            }
            if (tail == node) {
                tail = replacement;
            }
            return previousValue;
        }
    }

    public E remove(int index) {
        synchronized (mutex) {
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

    public E removeFirst() {
        return remove(0);
    }

    public E removeLast() {
        synchronized (mutex) {
            checkElementIndex(size - 1);
            return remove(size - 1);
        }
    }

    public boolean remove(Object value) {
        synchronized (mutex) {
            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (valuesEqual(value, current.value)) {
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
        synchronized (mutex) {
            Node<E> current = head;
            while (current != null) {
                if (valuesEqual(value, current.value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public int size() {
        synchronized (mutex) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (mutex) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    public Object[] toArray() {
        synchronized (mutex) {
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

    private boolean valuesEqual(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }
}
