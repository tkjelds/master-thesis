@ConcurrentList.ThreadSafe
public class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;
    private long version;

    public void add(E value) {
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            version++;
        }
    }

    public void add(int index, E value) {
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            ensureCapacity();

            Node<E> node = new Node<E>(value);
            if (index == size) {
                if (tail == null) {
                    head = node;
                } else {
                    tail.next = node;
                }
                tail = node;
            } else if (index == 0) {
                node.next = head;
                head = node;
            } else {
                Node<E> previous = nodeAt(index - 1);
                node.next = previous.next;
                previous.next = node;
            }
            size++;
            version++;
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
            version++;
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
            version++;
            return removed.value;
        }
    }

    public boolean remove(Object value) {
        synchronized (lock) {
            search:
            while (true) {
                long observedVersion = version;
                Node<E> previous = null;
                Node<E> current = head;

                while (current != null) {
                    boolean matches = value == null
                            ? current.value == null
                            : value.equals(current.value);

                    if (version != observedVersion) {
                        continue search;
                    }

                    if (matches) {
                        if (previous == null) {
                            head = current.next;
                        } else {
                            previous.next = current.next;
                        }
                        if (current == tail) {
                            tail = previous;
                        }
                        size--;
                        version++;
                        return true;
                    }

                    previous = current;
                    current = current.next;
                }
                return false;
            }
        }
    }

    public boolean contains(Object value) {
        return indexOf(value) >= 0;
    }

    public int indexOf(Object value) {
        synchronized (lock) {
            search:
            while (true) {
                long observedVersion = version;
                int index = 0;
                Node<E> current = head;

                while (current != null) {
                    boolean matches = value == null
                            ? current.value == null
                            : value.equals(current.value);

                    if (version != observedVersion) {
                        continue search;
                    }
                    if (matches) {
                        return index;
                    }

                    index++;
                    current = current.next;
                }
                return -1;
            }
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
            if (size != 0) {
                head = null;
                tail = null;
                size = 0;
                version++;
            }
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
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

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is too large");
        }
    }
}
