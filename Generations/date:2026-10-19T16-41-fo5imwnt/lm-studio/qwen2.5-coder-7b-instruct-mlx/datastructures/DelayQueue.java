import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}

    private final List<Entry<T>> queue = new ArrayList<>();
    private final Object lock = new Object();

    public void put(Entry<T> entry) throws InterruptedException {
        synchronized (lock) {
            while (isFull()) {
                lock.wait();
            }
            queue.add(entry);
            Collections.sort(queue, Entry.COMPARATOR);
            lock.notifyAll();
        }
    }

    public T take() throws InterruptedException {
        synchronized (lock) {
            while (isEmpty()) {
                lock.wait();
            }
            Entry<T> entry = queue.remove(0);
            lock.notifyAll();
            return entry.getValue();
        }
    }

    public boolean isFull() {
        // Implement your logic to check if the queue is full
        return false;
    }

    public boolean isEmpty() {
        // Implement your logic to check if the queue is empty
        return false;
    }

    public static class Entry<T> {
        private final long delay;
        private final long expirationTime;
        private final T value;

        public Entry(long delay, T value) {
            this.delay = delay;
            this.expirationTime = System.currentTimeMillis() + delay;
            this.value = value;
        }

        public T getValue() {
            return value;
        }

        public static final Comparator<Entry<?>> COMPARATOR = new Comparator<Entry<?>>() {
            @Override
            public int compare(Entry<?> e1, Entry<?> e2) {
                return Long.compare(e1.expirationTime, e2.expirationTime);
            }
        };
    }

    // Implement your logic for isFull and isEmpty methods
}
