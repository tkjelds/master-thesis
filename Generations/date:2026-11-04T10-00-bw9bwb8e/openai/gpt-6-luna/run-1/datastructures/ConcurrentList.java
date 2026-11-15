@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentList() {
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

    public void add(E value) {
        synchronized (lock) {
            Node<E> node = new Node<E>(value);
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
        synchronized (lock) {
            checkPositionIndex(index);

            Node<E> node = new Node<E>(value);
            if (index == 0) {
                node.next = head;
                head = node;
                if (tail == null) {
                    tail = node;
                }
            } else if (index == size) {
                tail.next = node;
                tail = node;
            } else {
                Node<E> previous = nodeAt(index - 1);
                node.next = previous.next;
                previous.next = node;
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
            E oldValue = node.value;
            node.value = value;
            return oldValue;
        }
    }

    public E removeAt(int index) {
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
            E value = removed.value;
            removed.next = null;
            removed.value = null;
            return value;
        }
    }

    public boolean removeValue(E value) {
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (valuesEqual(current.value, value)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    if (current == tail) {
                        tail = previous;
                    }

                    size--;
                    current.next = null;
                    current.value = null;
                    return true;
                }
                previous = current;
                current = current.next;
            }
            return false;
        }
    }

    public boolean contains(E value) {
        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                if (valuesEqual(current.value, value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public void clear() {
        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                Node<E> next = current.next;
                current.value = null;
                current.next = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    /**
     * Returns a point-in-time copy of the list. The returned array is independent
     * of the list and may be safely traversed without holding the list's lock.
     */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node<E> current = head;
            int index = 0;
            while (current != null) {
                result[index++] = current.value;
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

    private boolean valuesEqual(E first, E second) {
        return first == null ? second == null : first.equals(second);
    }
}
