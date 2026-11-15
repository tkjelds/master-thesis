/**
 * A thread-safe, unbounded doubly linked list.
 *
 * Null elements are not permitted. Poll and peek operations return null when
 * the list is empty; remove and get operations throw on an empty list.
 * Operations never wait for elements or capacity.
 */
@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E value;
        Node<E> previous;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

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

    public boolean add(E value) {
        addLast(value);
        return true;
    }

    public void addFirst(E value) {
        synchronized (lock) {
            requireValue(value);
            requireCapacity();

            Node<E> node = new Node<E>(value);
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
            size++;
        }
    }

    public void addLast(E value) {
        synchronized (lock) {
            requireValue(value);
            requireCapacity();

            Node<E> node = new Node<E>(value);
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
            size++;
        }
    }

    public void add(int index, E value) {
        synchronized (lock) {
            checkPositionIndex(index);
            requireValue(value);
            requireCapacity();

            Node<E> node = new Node<E>(value);
            if (index == size) {
                node.previous = last;
                if (last == null) {
                    first = node;
                } else {
                    last.next = node;
                }
                last = node;
            } else {
                Node<E> successor = nodeAt(index);
                Node<E> predecessor = successor.previous;
                node.previous = predecessor;
                node.next = successor;
                successor.previous = node;
                if (predecessor == null) {
                    first = node;
                } else {
                    predecessor.next = node;
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
            requireValue(value);
            Node<E> node = nodeAt(index);
            E oldValue = node.value;
            node.value = value;
            return oldValue;
        }
    }

    public E getFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            return first.value;
        }
    }

    public E getLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            return last.value;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.value;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return unlink(nodeAt(index));
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlink(first);
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("List is empty");
            }
            return unlink(last);
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            return first == null ? null : unlink(first);
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return last == null ? null : unlink(last);
        }
    }

    public void clear() {
        synchronized (lock) {
            first = null;
            last = null;
            size = 0;
        }
    }

    // The following helpers are called only while holding lock.
    private Node<E> nodeAt(int index) {
        if (index < size / 2) {
            Node<E> node = first;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }

        Node<E> node = last;
        for (int i = size - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
    }

    private E unlink(Node<E> node) {
        Node<E> predecessor = node.previous;
        Node<E> successor = node.next;

        if (predecessor == null) {
            first = successor;
        } else {
            predecessor.next = successor;
        }
        if (successor == null) {
            last = predecessor;
        } else {
            successor.previous = predecessor;
        }

        E value = node.value;
        node.value = null;
        node.previous = null;
        node.next = null;
        size--;
        return value;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private void requireCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is too large");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }
}
