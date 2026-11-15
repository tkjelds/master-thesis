@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private final int capacity;
    private final Object monitor;

    private Node<E> head;
    private Node<E> tail;
    private int size;

    private static final class Node<E> {
        E value;
        Node<E> prev;
        Node<E> next;

        Node() {
        }

        Node(E value) {
            this.value = value;
        }
    }

    public ConcurrentLinkedList() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentLinkedList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.monitor = new Object();
        this.head = new Node<E>();
        this.tail = new Node<E>();
        this.head.next = this.tail;
        this.tail.prev = this.head;
        this.size = 0;
    }

    public void add(E value) throws InterruptedException {
        putLast(value);
    }

    public void putFirst(E value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        synchronized (monitor) {
            while (size >= capacity) {
                monitor.wait();
            }
            insertFirstLocked(value);
            monitor.notifyAll();
        }
    }

    public void putLast(E value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        synchronized (monitor) {
            while (size >= capacity) {
                monitor.wait();
            }
            insertLastLocked(value);
            monitor.notifyAll();
        }
    }

    public boolean offerFirst(E value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        synchronized (monitor) {
            if (size >= capacity) {
                return false;
            }
            insertFirstLocked(value);
            monitor.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        synchronized (monitor) {
            if (size >= capacity) {
                return false;
            }
            insertLastLocked(value);
            monitor.notifyAll();
            return true;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E value = unlinkFirstLocked();
            monitor.notifyAll();
            return value;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E value = unlinkLastLocked();
            monitor.notifyAll();
            return value;
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E value = unlinkFirstLocked();
            monitor.notifyAll();
            return value;
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E value = unlinkLastLocked();
            monitor.notifyAll();
            return value;
        }
    }

    public E removeFirst() throws InterruptedException {
        return takeFirst();
    }

    public E removeLast() throws InterruptedException {
        return takeLast();
    }

    public E peekFirst() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            return head.next.value;
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            return tail.prev.value;
        }
    }

    public boolean remove(Object value) {
        synchronized (monitor) {
            Node<E> current = head.next;
            while (current != tail) {
                if (value == null) {
                    if (current.value == null) {
                        unlinkNodeLocked(current);
                        monitor.notifyAll();
                        return true;
                    }
                } else if (value.equals(current.value)) {
                    unlinkNodeLocked(current);
                    monitor.notifyAll();
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public boolean contains(Object value) {
        synchronized (monitor) {
            Node<E> current = head.next;
            while (current != tail) {
                if (value == null) {
                    if (current.value == null) {
                        return true;
                    }
                } else if (value.equals(current.value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            Node<E> current = head.next;
            while (current != tail) {
                Node<E> next = current.next;
                current.prev = null;
                current.next = null;
                current.value = null;
                current = next;
            }
            head.next = tail;
            tail.prev = head;
            size = 0;
            monitor.notifyAll();
        }
    }

    public Object[] toArray() {
        synchronized (monitor) {
            Object[] array = new Object[size];
            Node<E> current = head.next;
            int index = 0;
            while (current != tail) {
                array[index++] = current.value;
                current = current.next;
            }
            return array;
        }
    }

    private void insertFirstLocked(E value) {
        Node<E> newNode = new Node<E>(value);
        Node<E> next = head.next;
        newNode.prev = head;
        newNode.next = next;
        next.prev = newNode;
        head.next = newNode;
        size++;
    }

    private void insertLastLocked(E value) {
        Node<E> newNode = new Node<E>(value);
        Node<E> prev = tail.prev;
        newNode.prev = prev;
        newNode.next = tail;
        prev.next = newNode;
        tail.prev = newNode;
        size++;
    }

    private E unlinkFirstLocked() {
        Node<E> first = head.next;
        if (first == tail) {
            return null;
        }
        Node<E> next = first.next;
        head.next = next;
        next.prev = head;
        first.prev = null;
        first.next = null;
        E value = first.value;
        first.value = null;
        size--;
        return value;
    }

    private E unlinkLastLocked() {
        Node<E> last = tail.prev;
        if (last == head) {
            return null;
        }
        Node<E> prev = last.prev;
        prev.next = tail;
        tail.prev = prev;
        last.prev = null;
        last.next = null;
        E value = last.value;
        last.value = null;
        size--;
        return value;
    }

    private void unlinkNodeLocked(Node<E> node) {
        Node<E> prev = node.prev;
        Node<E> next = node.next;
        prev.next = next;
        next.prev = prev;
        node.prev = null;
        node.next = null;
        node.value = null;
        size--;
    }
}
