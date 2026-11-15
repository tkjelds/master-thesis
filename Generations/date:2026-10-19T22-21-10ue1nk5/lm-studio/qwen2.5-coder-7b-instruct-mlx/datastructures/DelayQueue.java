import java.util.LinkedList;
import java.util.Queue;

@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}

    private final Queue<Entry<T>> queue = new LinkedList<>();

    public synchronized void put(Entry<T> entry) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        queue.offer(entry);
        notifyAll();
    }

    public synchronized T take() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        T result = queue.poll().value;
        notifyAll();
        return result;
    }

    private boolean isFull() {
        return false; // Implement based on the capacity of your queue
    }

    private boolean isEmpty() {
        return queue.isEmpty();
    }

    public static class Entry<T> {
        private final long delay;
        private final T value;

        public Entry(long delay, T value) {
            this.delay = delay;
            this.value = value;
        }

        public long getDelay() {
            return delay;
        }

        public T getValue() {
            return value;
        }
    }
}
