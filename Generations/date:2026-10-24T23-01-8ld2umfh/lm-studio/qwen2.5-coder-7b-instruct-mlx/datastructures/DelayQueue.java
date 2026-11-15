import java.util.LinkedList;
import java.util.List;

@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}

    private List<Entry<T>> entries = new LinkedList<>();
    private final Object lock = new Object();

    public static class Entry<T> {
        long expiration;
        T item;

        Entry(long expiration, T item) {
            this.expiration = expiration;
            this.item = item;
        }
    }

    public boolean add(T item, long delay) {
        synchronized (lock) {
            Entry<T> entry = new Entry<>(System.currentTimeMillis() + delay, item);
            entries.add(entry);
            entries.sort((e1, e2) -> Long.compare(e1.expiration, e2.expiration));
            lock.notifyAll();
            return true;
        }
    }

    public T take() throws InterruptedException {
        while (true) {
            synchronized (lock) {
                if (!entries.isEmpty()) {
                    Entry<T> entry = entries.remove(0);
                    return entry.item;
                }
                lock.wait();
            }
        }
    }

    public T peek() {
        synchronized (lock) {
            if (!entries.isEmpty()) {
                return entries.get(0).item;
            }
            return null;
        }
    }

    public boolean remove(T item) {
        synchronized (lock) {
            for (Entry<T> entry : entries) {
                if (entry.item.equals(item)) {
                    entries.remove(entry);
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return entries.isEmpty();
        }
    }

    public void clear() {
        synchronized (lock) {
            entries.clear();
            lock.notifyAll();
        }
    }

    public boolean contains(T item) {
        synchronized (lock) {
            for (Entry<T> entry : entries) {
                if (entry.item.equals(item)) {
                    return true;
                }
            }
            return false;
        }
    }
}
