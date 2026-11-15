@DictionaryBag.ThreadSafe
public class DictionaryBag {
    @interface ThreadSafe {}

    private Object[] items;
    private int size;
    private final Object monitor = new Object();

    public DictionaryBag(int capacity) {
        items = new Object[capacity];
        size = 0;
    }

    public boolean add(Object item) {
        synchronized (monitor) {
            if (size == items.length) {
                return false;
            }
            items[size++] = item;
            monitor.notifyAll();
            return true;
        }
    }

    public Object remove() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            Object item = items[--size];
            items[size] = null;
            return item;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean contains(Object item) {
        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                if (items[i].equals(item)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void clear() {
        synchronized (monitor) {
            items = new Object[items.length];
            size = 0;
        }
    }
}
