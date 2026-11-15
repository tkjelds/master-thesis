import java.util.ArrayList;
import java.util.List;

@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}

    private final List<Element<T>> queue = new ArrayList<>();
    private final Object lock = new Object();

    public void put(T item, long delay) throws InterruptedException {
        Element<T> element = new Element<>(item, System.currentTimeMillis() + delay);
        synchronized (lock) {
            queue.add(element);
            lock.notifyAll();
        }
    }

    public T take() throws InterruptedException {
        synchronized (lock) {
            while (queue.isEmpty()) {
                lock.wait();
            }
            Element<T> element = queue.remove(0);
            return element.item;
        }
    }

    public int size() {
        synchronized (lock) {
            return queue.size();
        }
    }

    private static class Element<T> implements Comparable<Element<T>> {
        final T item;
        final long expireTime;

        Element(T item, long expireTime) {
            this.item = item;
            this.expireTime = expireTime;
        }

        @Override
        public int compareTo(Element<T> other) {
            return Long.compare(this.expireTime, other.expireTime);
        }
    }

    public void clear() {
        synchronized (lock) {
            queue.clear();
            lock.notifyAll();
        }
    }

    public boolean remove(Object o) {
        synchronized (lock) {
            boolean removed = queue.remove(o);
            if (removed) {
                lock.notifyAll();
            }
            return removed;
        }
    }

    public boolean offer(T item, long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        long nanosTimeout = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanosTimeout;
        synchronized (lock) {
            while (queue.isEmpty()) {
                long waitTime = deadline - System.nanoTime();
                if (waitTime <= 0) {
                    return false;
                }
                lock.wait(waitTime);
            }
            T itemInQueue = queue.peek();
            if (itemInQueue != null) {
                return false;
            }
            Element<T> element = new Element<>(item, System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(nanosTimeout));
            queue.add(element);
            lock.notifyAll();
            return true;
        }
    }

    public T poll(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        long nanosTimeout = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanosTimeout;
        synchronized (lock) {
            while (queue.isEmpty()) {
                long waitTime = deadline - System.nanoTime();
                if (waitTime <= 0) {
                    return null;
                }
                lock.wait(waitTime);
            }
            Element<T> element = queue.remove(0);
            return element.item;
        }
    }

    public boolean offer(T item) {
        try {
            put(item, 0);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public T poll() {
        try {
            return take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public T peek() {
        synchronized (lock) {
            if (!queue.isEmpty()) {
                return queue.get(0).item;
            }
            return null;
        }
    }

    public long remainingCapacity() {
        return Integer.MAX_VALUE; // DelayQueue has no capacity limits
    }
}
