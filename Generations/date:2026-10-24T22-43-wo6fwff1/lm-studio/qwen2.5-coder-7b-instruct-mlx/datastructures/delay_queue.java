```java
import java.util.LinkedList;
import java.util.Queue;

class DelayQueue<T> {
    private final Queue<Entry<T>> queue = new LinkedList<>();

    public void put(T item, long delay) {
        synchronized (queue) {
            queue.add(new Entry<>(item, System.currentTimeMillis() + delay));
            queue.notifyAll();
        }
    }

    public T take() throws InterruptedException {
        synchronized (queue) {
            while (queue.isEmpty()) {
                queue.wait();
            }
            Entry<T> entry = queue.poll();
            while (entry.expiration > System.currentTimeMillis()) {
                queue.wait(entry.expiration - System.currentTimeMillis());
            }
            return entry.item;
        }
    }

    private static class Entry<T> {
        final T item;
        final long expiration;

        Entry(T item, long expiration) {
            this.item = item;
            this.expiration = expiration;
        }
    }
}
```
