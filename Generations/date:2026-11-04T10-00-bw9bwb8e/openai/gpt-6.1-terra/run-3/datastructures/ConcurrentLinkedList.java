/**
 * A thread-safe, unbounded doubly linked list.
 *
 * Operations do not wait for elements. Removal and access at either end throw
 * IllegalStateException when the list is empty. Invalid indices throw
 * IndexOutOfBoundsException. Null elements are permitted.
 */
@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T item;
        Node<T> previous;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;
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

    public boolean add(E element) {
        synchronized (lock) {
            linkLast(element);
            return true;
        }
    }

    public void addFirst(E element) {
        synchronized (lock) {
            checkCapacity();
            Node<E> node = new Node<E>(element);
            changeStamp = new Object();

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

    public void addLast(E element) {
        synchronized (lock) {
            linkLast(element);
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            checkPositionIndex(index);
            if (index == size) {
                linkLast(element);
                return;
            }

            checkCapacity();
            Node<E> successor = nodeAt(index);
            Node<E> predecessor = successor.previous;
            Node<E> node = new Node<E>(element);
            changeStamp = new Object();

            node.previous = predecessor;
            node.next = successor;
            successor.previous = node;
            if (predecessor == null) {
                first = node;
            } else {
                predecessor.next = node;
            }
            size++;
        }
    }

    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).item;
        }
    }

    public E getFirst() {
        synchronized (lock) {
            requireNonEmpty();
            return first.item;
        }
    }

    public E getLast() {
        synchronized (lock) {
            requireNonEmpty();
            return last.item;
        }
    }

    public E set(int index, E element) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E old = node.item;
            changeStamp = new Object();
            node.item = element;
            return old;
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

    /**
     * Removes the first element equal to the supplied value.
     * Equality is evaluated while holding this list's monitor.
     */
    public boolean remove(Object value) {
        synchronized (lock) {
            Node<E> node = first;
            while (node != null) {
                if (matches(value, node.item)) {
                    unlink(node);
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public boolean contains(Object value) {
        return indexOf(value) >= 0;
    }

    public int indexOf(Object value) {
        synchronized (lock) {
            int index = 0;
            Node<E> node = first;
            while (node != null) {
                if (matches(value, node.item)) {
                    return index;
                }
                node = node.next;
                index++;
            }
            return -1;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                changeStamp = new Object();
                first = null;
                last = null;
                size = 0;
            }
        }
    }

    // The following helpers are called only while holding lock.

    private void linkLast(E element) {
        checkCapacity();
        Node<E> node = new Node<E>(element);
        changeStamp = new Object();

        node.previous = last;
        if (last == null) {
            first = node;
        } else {
            last.next = node;
        }
        last = node;
        size++;
    }

    private E unlink(Node<E> node) {
        E old = node.item;
        Node<E> predecessor = node.previous;
        Node<E> successor = node.next;
        changeStamp = new Object();

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
        size--;

        node.item = null;
        node.previous = null;
        node.next = null;
        return old;
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

    private boolean matches(Object value, E item) {
        if (value == item) {
            return true;
        }
        if (value == null) {
            return false;
        }

        Object stamp = changeStamp;
        boolean result = value.equals(item);
        if (stamp != changeStamp) {
            throw new IllegalStateException(
                "List modified reentrantly during equality comparison");
        }
        return result;
    }

    private void checkCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is at maximum size");
        }
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
