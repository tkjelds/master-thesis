@DictionaryBag.ThreadSafe
public class DictionaryBag {
    @interface ThreadSafe {}

    private final Object[] items;
    private int size = 0;

    public DictionaryBag(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        items = new Object[capacity];
    }

    public synchronized void add(Object item) throws InterruptedException {
        while (isFull()) wait();
        items[size++] = item;
        notifyAll();
    }

    public synchronized Object remove() throws InterruptedException {
        while (isEmpty()) wait();
        Object item = items[--size];
        notifyAll();
        return item;
    }

    public synchronized boolean contains(Object item) {
        for (int i = 0; i < size; i++) {
            if (items[i].equals(item)) return true;
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    private boolean isFull() {
        return size == items.length;
    }

    private boolean isEmpty() {
        return size == 0;
    }
}
