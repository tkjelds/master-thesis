/**
 * An unbounded, thread-safe, singly linked list.
 *
 * Null elements are permitted. Operations never wait for an element or for
 * capacity, although they may wait to acquire the list's monitor. Removing
 * from an empty list throws IllegalStateException. Invalid indices throw
 * IndexOutOfBoundsException.
 *
 * Each public operation is atomic with respect to the other operations on
 * this list. Elements themselves are not protected by the list's monitor.
 */
@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E value;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
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
            checkCapacity();
            Node<E> node = new Node<E>(value);
            node.next = head;
            head = node;
            if (size == 0) {
                tail = node;
            }
            size++;
        }
    }

    public void addLast(E value) {
        synchronized (lock) {
            checkCapacity();
            Node<E> node = new Node<E>(value);
            if (size == 0) {
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
            checkCapacity();
            Node<E> node = new Node<E>(value);
            if (index == 0) {
                node.next = head;
                head = node;
                if (size == 0) {
                    tail = node;
                }
            } else {
                Node<E> previous = nodeAt(index - 1);
                node.next = previous.next;
                previous.next = node;
                if (index == size) {
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

    public E set(int index, E value) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E oldValue = node.value;
            node.value = value;
            return oldValue;
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            checkNotEmpty();
            return removeAt(0);
        }
    }

    public E removeLast() {
        synchronized (lock) {
            checkNotEmpty();
            return removeAt(size - 1);
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return removeAt(index);
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    private E removeAt(int index) {
        Node<E> removed;
        if (index == 0) {
            removed = head;
            head = removed.next;
            if (size == 1) {
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

    private Node<E> nodeAt(int index) {
        Node<E> node = head;
        for (int i = 0; i < index; i++) {
            node = node.next;
        }
        return node;
    }

    private void checkNotEmpty() {
        if (size == 0) {
            throw new IllegalStateException("List is empty");
        }
    }

    private void checkCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List size limit reached");
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
