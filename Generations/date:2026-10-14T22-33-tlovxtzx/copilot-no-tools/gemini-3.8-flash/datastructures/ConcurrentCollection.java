@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    private static final class Node<E> {
        private final E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    public ConcurrentCollection() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean add(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            append(element);
            lock.notifyAll();
            return true;
        }
    }

    public void put(E element) throws InterruptedException {
        add(element);
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            append(element);
            lock.notifyAll();
            return true;
        }
    }

    public boolean tryAdd(E element) {
        return offer(element);
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E value = removeHead();
            lock.notifyAll();
            return value;
        }
    }

    public E remove() throws InterruptedException {
        return take();
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E value = removeHead();
            lock.notifyAll();
            return value;
        }
    }

    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return head.value;
        }
    }

    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            Node<E> curr = head;
            while (curr != null) {
                if (element.equals(curr.value)) {
                    return true;
                }
                curr = curr.next;
            }
            return false;
        }
    }

    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            Node<E> prev = null;
            Node<E> curr = head;
            while (curr != null) {
                if (element.equals(curr.value)) {
                    if (prev == null) {
                        head = curr.next;
                    } else {
                        prev.next = curr.next;
                    }
                    if (curr == tail) {
                        tail = prev;
                    }
                    curr.next = null;
                    size--;
                    lock.notifyAll();
                    return true;
                }
                prev = curr;
                curr = curr.next;
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

    public int capacity() {
        synchronized (lock) {
            return capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] array = new Object[size];
            Node<E> curr = head;
            int index = 0;
            while (curr != null) {
                array[index++] = curr.value;
                curr = curr.next;
            }
            return array;
        }
    }

    private void append(E element) {
        Node<E> node = new Node<E>(element);
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
    }

    private E removeHead() {
        Node<E> node = head;
        head = node.next;
        node.next = null;
        size--;
        if (head == null) {
            tail = null;
        }
        return node.value;
    }
}
