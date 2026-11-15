import java.util.ArrayList;
import java.util.List;

@PriorityBlockingQueue.ThreadSafe
public class PriorityBlockingQueue<E> {
    @interface ThreadSafe {}

    private final List<E> elements;
    private final int capacity;

    public PriorityBlockingQueue(int capacity) {
        this.capacity = capacity;
        this.elements = new ArrayList<>(capacity);
    }

    public synchronized void put(E element) throws InterruptedException {
        while (elements.size() == capacity) {
            wait();
        }
        elements.add(element);
        notifyAll();
    }

    public synchronized E take() throws InterruptedException {
        while (elements.isEmpty()) {
            wait();
        }
        E element = elements.remove(0);
        notifyAll();
        return element;
    }

    public synchronized boolean offer(E element) {
        if (elements.size() == capacity) {
            return false;
        }
        elements.add(element);
        notifyAll();
        return true;
    }

    public synchronized E poll() {
        if (elements.isEmpty()) {
            return null;
        }
        E element = elements.remove(0);
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return elements.size();
    }
}
