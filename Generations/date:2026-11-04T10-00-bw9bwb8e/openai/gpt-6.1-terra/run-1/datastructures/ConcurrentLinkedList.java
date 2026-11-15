/**
 * An unbounded, thread-safe doubly linked list.
 *
 * Null elements are permitted. Indexed operations use zero-based indices.
 * Accessing or removing an endpoint of an empty list throws
 * IllegalStateException; no operation waits for elements.
 *
 * Equality-based operations call the supplied object's equals method.
 * If that callback modifies this list, the operation throws
 * IllegalStateException rather than continuing with stale links.
 */
@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    // Replaced on every mutation, including replacement of an element.
    private Object changeStamp = new Object();

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
        synchronized (lock) {
            linkLast(value);
            return true;
        }
    }

    public void addFirst(E value) {
        synchronized (lock) {
            linkBefore(value, first);
        }
    }

    public void addLast(E value) {
        synchronized (lock) {
            linkLast(value);
        }
    }

    public void add(int index, E value) {
        synchronized (lock) {
            checkPositionIndex(index);
            linkBefore(value, index == size ? null : nodeAt(index));
        }
    }

    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    public E getFirst() {
        synchronized (lock) {
            requireNonEmpty();
            return first.value;
        }
    }

    public E getLast() {
        synchronized (lock) {
            requireNonEmpty();
            return last.value;
        }
    }

    public E set(int index, E value) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            Object newStamp = new Object();
            E oldValue = node.value;
            node.value = value;
            changeStamp = newStamp;
            return oldValue;
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
            requireNonEmpty();
            return unlink(first);
        }
    }

    public E removeLast() {
        synchronized (lock) {
            requireNonEmpty();
            return unlink(last);
        }
    }

    public boolean contains(Object value) {
        synchronized (lock) {
            return find(value) != null;
        }
    }

    public boolean remove(Object value) {
        synchronized (lock) {
            Node<E> node = find(value);
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                Object newStamp = new Object();
                first = null;
                last = null;
                size = 0;
                changeStamp = newStamp;
            }
        }
    }

    // All private helpers are called with lock held.

    private void linkLast(E value) {
        linkBefore(value, null);
    }

    private void linkBefore(E value, Node<E> successor) {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List size limit reached");
        }

        Node<E> predecessor = successor == null ? last : successor.previous;
        Node<E> node = new Node<E>(value);
        Object newStamp = new Object();

        node.previous = predecessor;
        node.next = successor;
        if (predecessor == null) {
            first = node;
        } else {
            predecessor.next = node;
        }
        if (successor == null) {
            last = node;
        } else {
            successor.previous = node;
        }
        size++;
        changeStamp = newStamp;
    }

    private E unlink(Node<E> node) {
        Object newStamp = new Object();
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
        changeStamp = newStamp;
        return value;
    }

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

    private Node<E> find(Object value) {
        Node<E> node = first;
        while (node != null) {
            Object stamp = changeStamp;
            boolean matches = value == node.value
                    || (value != null && value.equals(node.value));
            if (stamp != changeStamp) {
                throw new IllegalStateException(
                        "List modified during equality comparison");
            }
            if (matches) {
                return node;
            }
            node = node.next;
        }
        return null;
    }

    private void requireNonEmpty() {
        if (size == 0) {
            throw new IllegalStateException("List is empty");
        }
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
}
