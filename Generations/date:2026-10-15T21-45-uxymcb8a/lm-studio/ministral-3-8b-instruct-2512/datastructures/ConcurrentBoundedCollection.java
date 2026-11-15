@ConcurrentBoundedCollection.ThreadSafe
public class ConcurrentBoundedCollection {
    @interface ThreadSafe {}

    private final int capacity;
    private final Object[] elements;
    private volatile int count = 0;

    public ConcurrentBoundedCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public void add(Object element) throws InterruptedException {
        synchronized (this) {
            while (count == capacity) {
                wait();
            }
            elements[count] = element;
            count++;
            notifyAll();
        }
    }

    public Object remove() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            Object element = elements[count - 1];
            count--;
            notifyAll();
            return element;
        }
    }

    public boolean contains(Object element) {
        synchronized (this) {
            for (int i = 0; i < count; i++) {
                if (elements[i] == element || elements[i] != null && elements[i].equals(element)) {
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return count == capacity;
        }
    }

    public void clear() throws InterruptedException {
        synchronized (this) {
            while (count != 0) {
                wait();
            }
            notifyAll();
        }
    }

    public void print() {
        synchronized (this) {
            System.out.print("[");
            for (int i = 0; i < count; i++) {
                System.out.print(elements[i]);
                if (i != count - 1) {
                    System.out.print(", ");
                }
            }
            System.out.println("]");
        }
    }
}
