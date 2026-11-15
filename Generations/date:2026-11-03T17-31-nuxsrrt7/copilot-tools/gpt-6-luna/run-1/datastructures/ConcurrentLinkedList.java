@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E item;
        private Node<E> previous;
        private Node<E> next;

        private Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    public void addFirst(E item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<E> node = new Node<E>(item);
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

    public void addLast(E item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<E> node = new Node<E>(item);
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

    public boolean add(E item) {
        addLast(item);
        return true;
    }

    public E removeFirst() {
        synchronized (lock) {
            if (first == null) {
                return null;
            }
            return unlink(first);
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (last == null) {
                return null;
            }
            return unlink(last);
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            return unlink(nodeAt(index));
        }
    }

    public boolean removeValue(Object value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            Node<E> node = first;
            while (node != null) {
                if (value.equals(node.item)) {
                    unlink(node);
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.item;
        }
    }

    public E get(int index) {
        synchronized (lock) {
            return nodeAt(index).item;
        }
    }

    public E set(int index, E item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<E> node = nodeAt(index);
            E previous = node.item;
            node.item = item;
            return previous;
        }
    }

    public boolean contains(Object value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            Node<E> node = first;
            while (node != null) {
                if (value.equals(node.item)) {
                    return true;
                }
                node = node.next;
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
            Node<E> node = first;
            while (node != null) {
                Node<E> next = node.next;
                node.item = null;
                node.previous = null;
                node.next = null;
                node = next;
            }
            first = null;
            last = null;
            size = 0;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node<E> node = first;
            for (int i = 0; i < size; i++) {
                result[i] = node.item;
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

        E item = node.item;
        node.item = null;
        node.previous = null;
        node.next = null;
        size--;
        return item;
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not supported");
        }
    }
}
