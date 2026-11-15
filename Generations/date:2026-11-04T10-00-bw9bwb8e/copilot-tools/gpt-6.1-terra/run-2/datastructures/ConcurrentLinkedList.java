/**
 * A thread-safe, unbounded, doubly linked list.
 *
 * Null elements are permitted. Operations do not wait for elements:
 * removing from an empty list throws IllegalStateException. Invalid indices
 * throw IndexOutOfBoundsException. Insertion throws IllegalStateException
 * if the list has reached Integer.MAX_VALUE elements.
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

    public void add(E value) {
        synchronized (lock) {
            linkBetween(value, last, null);
        }
    }

    public void addFirst(E value) {
        synchronized (lock) {
            linkBetween(value, null, first);
        }
    }

    public void addLast(E value) {
        synchronized (lock) {
            linkBetween(value, last, null);
        }
    }

    public void add(int index, E value) {
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
            }
            if (index == size) {
                linkBetween(value, last, null);
            } else {
                Node<E> next = nodeAt(index);
                linkBetween(value, next.previous, next);
            }
        }
    }

    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return nodeAt(index).value;
        }
    }

    public E set(int index, E value) {
        synchronized (lock) {
            checkIndex(index);
            Node<E> node = nodeAt(index);
            E oldValue = node.value;
            node.value = value;
            return oldValue;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkIndex(index);
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

    public void clear() {
        synchronized (lock) {
            first = null;
            last = null;
            size = 0;
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
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

    private void linkBetween(E value, Node<E> previous, Node<E> next) {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is at maximum size");
        }

        Node<E> node = new Node<E>(value);
        node.previous = previous;
        node.next = next;

        if (previous == null) {
            first = node;
        } else {
            previous.next = node;
        }

        if (next == null) {
            last = node;
        } else {
            next.previous = node;
        }

        size++;
    }

    private E unlink(Node<E> node) {
        Node<E> previous = node.previous;
        Node<E> next = node.next;

        if (previous == null) {
            first = next;
        } else {
            previous.next = next;
        }

        if (next == null) {
            last = previous;
        } else {
            next.previous = previous;
        }

        size--;
        return node.value;
    }
}
