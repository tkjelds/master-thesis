@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}

    private final boolean[] elements;
    private int size;

    public ConcurrentSet(int capacity) {
        this.elements = new boolean[capacity];
        this.size = 0;
    }

    public synchronized boolean add(int element) {
        if (element < 0 || element >= elements.length) {
            throw new IndexOutOfBoundsException("Element out of bounds");
        }
        if (elements[element]) return false;
        elements[element] = true;
        size++;
        notifyAll();
        return true;
    }

    public synchronized boolean remove(int element) {
        if (element < 0 || element >= elements.length) {
            throw new IndexOutOfBoundsException("Element out of bounds");
        }
        if (!elements[element]) return false;
        elements[element] = false;
        size--;
        notifyAll();
        return true;
    }

    public synchronized boolean contains(int element) {
        if (element < 0 || element >= elements.length) {
            throw new IndexOutOfBoundsException("Element out of bounds");
        }
        return elements[element];
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        for (int i = 0; i < elements.length; i++) {
            elements[i] = false;
        }
        size = 0;
        notifyAll();
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
