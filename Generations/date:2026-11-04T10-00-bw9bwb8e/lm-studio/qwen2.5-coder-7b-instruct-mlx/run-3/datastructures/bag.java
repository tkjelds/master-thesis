@Bag.ThreadSafe
class Bag {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size = 0;

    public Bag(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        elements = new Object[capacity];
    }

    public synchronized boolean add(Object element) {
        if (size == elements.length) return false;
        elements[size++] = element;
        notifyAll();
        return true;
    }

    public synchronized Object remove() throws InterruptedException {
        while (size == 0) wait();
        Object element = elements[--size];
        elements[size] = null; // Help garbage collection
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return size;
    }
}
