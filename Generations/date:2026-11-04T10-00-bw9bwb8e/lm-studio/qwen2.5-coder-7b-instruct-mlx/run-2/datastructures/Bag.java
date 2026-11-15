@Bag.ThreadSafe
public class Bag {
    @interface ThreadSafe {}

    private final Object[] items;
    private int size = 0;

    public Bag(int capacity) {
        this.items = new Object[capacity];
    }

    public synchronized boolean add(Object item) {
        if (size < items.length) {
            items[size++] = item;
            return true;
        }
        return false;
    }

    public synchronized Object remove() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        Object item = items[--size];
        notifyAll();
        return item;
    }

    public synchronized boolean contains(Object item) {
        for (int i = 0; i < size; i++) {
            if (items[i].equals(item)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        while (size > 0) {
            remove();
        }
    }
}
