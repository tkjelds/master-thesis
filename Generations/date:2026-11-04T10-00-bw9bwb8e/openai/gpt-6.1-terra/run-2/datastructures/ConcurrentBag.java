@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;

    private final Object lock = new Object();
    private final Object[] elements;
    private int size;

    public ConcurrentBag() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    // Null elements are not permitted. Returns false if the bag is full.
    public boolean add(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insert(element);
            return true;
        }
    }

    // Waits for space if the bag is full.
    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insert(element);
        }
    }

    // Removes an arbitrary element, or returns null if the bag is empty.
    public E remove() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return extract();
        }
    }

    // Waits for an element if the bag is empty.
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return extract();
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
        return elements.length;
    }

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                for (int i = 0; i < size; i++) {
                    elements[i] = null;
                }
                size = 0;
                lock.notifyAll();
            }
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Called only while holding lock.
    private void insert(E element) {
        elements[size++] = element;
        lock.notifyAll();
    }

    // Called only while holding lock and when the bag is nonempty.
    @SuppressWarnings("unchecked")
    private E extract() {
        int index = --size;
        E element = (E) elements[index];
        elements[index] = null;
        lock.notifyAll();
        return element;
    }
}
