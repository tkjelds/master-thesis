/**
 * A bounded, thread-safe deque.
 *
 * Null elements are not permitted. The default capacity is 16.
 * offer operations return false when full; poll and peek operations return
 * null when empty. add and remove operations throw IllegalStateException
 * when they cannot complete. put and take operations block interruptibly.
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int size;

    public ConcurrentDeque() {
        this(16);
    }

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    private static void checkElement(Object element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Called only while holding lock. Avoids overflow in head + offset.
    private int index(int offset) {
        int untilEnd = elements.length - head;
        return offset < untilEnd ? head + offset : offset - untilEnd;
    }

    // The following four helpers are called only while holding lock.
    private void insertFirst(E element) {
        head = head == 0 ? elements.length - 1 : head - 1;
        elements[head] = element;
        size++;
        lock.notifyAll();
    }

    private void insertLast(E element) {
        elements[index(size)] = element;
        size++;
        lock.notifyAll();
    }

    @SuppressWarnings("unchecked")
    private E extractFirst() {
        E result = (E) elements[head];
        elements[head] = null;
        size--;
        if (size == 0) {
            head = 0;
        } else {
            head = head == elements.length - 1 ? 0 : head + 1;
        }
        lock.notifyAll();
        return result;
    }

    @SuppressWarnings("unchecked")
    private E extractLast() {
        int position = index(size - 1);
        E result = (E) elements[position];
        elements[position] = null;
        size--;
        if (size == 0) {
            head = 0;
        }
        lock.notifyAll();
        return result;
    }

    public int capacity() {
        return elements.length;
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

    public int remainingCapacity() {
        synchronized (lock) {
            return elements.length - size;
        }
    }

    public boolean offerFirst(E element) {
        checkElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insertFirst(element);
            return true;
        }
    }

    public boolean offerLast(E element) {
        checkElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insertLast(element);
            return true;
        }
    }

    public void addFirst(E element) {
        checkElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                throw new IllegalStateException("Deque is full");
            }
            insertFirst(element);
        }
    }

    public void addLast(E element) {
        checkElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                throw new IllegalStateException("Deque is full");
            }
            insertLast(element);
        }
    }

    public void putFirst(E element) throws InterruptedException {
        checkElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insertFirst(element);
        }
    }

    public void putLast(E element) throws InterruptedException {
        checkElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insertLast(element);
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            return size == 0 ? null : extractFirst();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return size == 0 ? null : extractLast();
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            return extractFirst();
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            return extractLast();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return extractFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return extractLast();
        }
    }

    @SuppressWarnings("unchecked")
    public E peekFirst() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[head];
        }
    }

    @SuppressWarnings("unchecked")
    public E peekLast() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[index(size - 1)];
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size == 0) {
                return;
            }
            for (int i = 0; i < size; i++) {
                elements[index(i)] = null;
            }
            head = 0;
            size = 0;
            lock.notifyAll();
        }
    }
}
