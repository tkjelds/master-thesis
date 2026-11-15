@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        Node<E> previous;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public void addFirst(E item) {
        synchronized (lock) {
            Node<E> node = new Node<>(item);
            node.next = head;
            if (head == null) {
                tail = node;
            } else {
                head.previous = node;
            }
            head = node;
            size++;
        }
    }

    public void addLast(E item) {
        synchronized (lock) {
            Node<E> node = new Node<>(item);
            node.previous = tail;
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    public void add(E item) {
        addLast(item);
    }

    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            return unlink(head);
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }
            return unlink(tail);
        }
    }

    public boolean remove(E item) {
        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                if (current.item == item
                        || (item != null && item.equals(current.item))) {
                    unlink(current);
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            Node<E> current;
            if (index < size / 2) {
                current = head;
                for (int i = 0; i < index; i++) {
                    current = current.next;
                }
            } else {
                current = tail;
                for (int i = size - 1; i > index; i--) {
                    current = current.previous;
                }
            }
            return current.item;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    public boolean contains(E item) {
        synchronized (lock) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (current.item == item
                        || (item != null && item.equals(current.item))) {
                    return true;
                }
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
            Node<E> current = head;
            while (current != null) {
                Node<E> next = current.next;
                current.previous = null;
                current.next = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    private E unlink(Node<E> node) {
        Node<E> previous = node.previous;
        Node<E> next = node.next;

        if (previous == null) {
            head = next;
        } else {
            previous.next = next;
        }

        if (next == null) {
            tail = previous;
        } else {
            next.previous = previous;
        }

        node.previous = null;
        node.next = null;
        size--;
        return node.item;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }
}
