import java.util.ArrayList;
import java.util.List;

@DelayQueue.ThreadSafe
public class DelayQueue<E> {
    @interface ThreadSafe {}

    private final List<E> queue = new ArrayList<>();
    private final Object lock = new Object();
    private long headTime = Long.MAX_VALUE;

    public void put(E element, long delay) throws InterruptedException {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long endTime = now + delay;
            while (true) {
                if (headTime > endTime) {
                    headTime = endTime;
                    queue.add(element);
                    lock.notifyAll();
                    return;
                }
                long waitTime = headTime - now;
                if (waitTime <= 0) {
                    break; // headTime should be updated by poll or take
                }
                lock.wait(waitTime);
            }
        }
    }

    public E poll() {
        synchronized (lock) {
            while (queue.isEmpty()) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            E element = queue.remove(0);
            if (!queue.isEmpty()) {
                headTime = ((Long) queue.get(0)).longValue();
            } else {
                headTime = Long.MAX_VALUE;
            }
            return element;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (queue.isEmpty()) {
                lock.wait();
            }
            E element = queue.remove(0);
            if (!queue.isEmpty()) {
                headTime = ((Long) queue.get(0)).longValue();
            } else {
                headTime = Long.MAX_VALUE;
            }
            return element;
        }
    }

    public int size() {
        synchronized (lock) {
            return queue.size();
        }
    }
}
