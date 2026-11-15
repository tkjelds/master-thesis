@Bag.ThreadSafe
public class Bag {
    @interface ThreadSafe {}

    private Object[] items;
    private int size = 0;

    public Bag(int capacity) {
        items = new Object[capacity];
    }

    public synchronized void add(Object item) throws InterruptedException {
        while (size == items.length) {
            wait();
        }
        items[size++] = item;
        notifyAll();
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

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
