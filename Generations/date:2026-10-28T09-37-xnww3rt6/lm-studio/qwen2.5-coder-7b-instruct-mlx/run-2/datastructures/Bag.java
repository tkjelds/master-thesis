@Bag.ThreadSafe
public class Bag {
    @interface ThreadSafe {}

    private int[] elements;
    private int size;

    public Bag(int capacity) {
        elements = new int[capacity];
        size = 0;
    }

    public synchronized boolean add(int element) {
        if (size == elements.length) {
            return false;
        }
        elements[size++] = element;
        notifyAll();
        return true;
    }

    public synchronized int remove() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        int element = elements[0];
        for (int i = 1; i < size; i++) {
            elements[i - 1] = elements[i];
        }
        size--;
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return size;
    }
}
