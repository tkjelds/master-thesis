import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

@TaggedCapacityBuffer.ThreadSafe
class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private final int capacity;
    private byte[] buffer;
    private String[] tags;
    private int head = 0;
    private int tail = 0;
    private int occupancy = 0;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lock = new Object();

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        buffer = new byte[capacity];
        tags = new String[capacity];
    }

    public int offer(byte[] payload, String[] tags, String[] evictionTags) {
        if (payload == null || tags == null || payload.length <= 0) return -1;
        synchronized (lock) {
            if (closed.get()) throw new IllegalStateException("Buffer is closed");
            while (true) {
                int evictIndex = -1;
                for (int i = head; i < tail; i++) {
                    if (matches(evictionTags, tags[i])) {
                        evictIndex = i;
                        break;
                    }
                }
                if (evictIndex != -1) {
                    evict(evictIndex);
                    continue;
                }
                if (occupancy + payload.length <= capacity) {
                    break;
                } else {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
            }
            buffer[tail] = payload.clone()[0];
            tags[tail] = Arrays.copyOf(tags, tags.length)[0];
            tail = (tail + 1) % capacity;
            occupancy += payload.length;
            lock.notifyAll();
            return tail - 1;
        }
    }

    public void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        if (payload == null || tags == null || payload.length <= 0) return;
        synchronized (lock) {
            if (closed.get()) throw new IllegalStateException("Buffer is closed");
            while (true) {
                int evictIndex = -1;
                for (int i = head; i < tail; i++) {
                    if (matches(tags, tags[i])) {
                        evictIndex = i;
                        break;
                    }
                }
                if (evictIndex != -1) {
                    evict(evictIndex);
                    continue;
                }
                if (occupancy + payload.length <= capacity) {
                    break;
                } else {
                    lock.wait();
                }
            }
            buffer[tail] = payload.clone()[0];
            tags[tail] = Arrays.copyOf(tags, tags.length)[0];
            tail = (tail + 1) % capacity;
            occupancy += payload.length;
        }
    }

    public byte[] takeMatching(String tag) throws InterruptedException {
        synchronized (lock) {
            if (closed.get()) return null;
            while (head == tail) {
                lock.wait();
            }
            int index = head;
            while (!matches(tag, tags[index])) {
                index = (index + 1) % capacity;
                if (head == tail) {
                    lock.wait();
                    index = head;
                }
            }
            byte[] result = buffer[index];
            tags[index] = null;
            head = (head + 1) % capacity;
            occupancy -= result.length;
            lock.notifyAll();
            return result;
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == this || closed.get() || destination.closed.get()) return false;
        synchronized (lock) {
            while (head == tail) {
                lock.wait();
            }
            int index = head;
            while (!matches(tag, tags[index])) {
                index = (index + 1) % capacity;
                if (head == tail) {
                    lock.wait();
                    index = head;
                }
            }
            synchronized (destination.lock) {
                if (destination.occupancy + buffer[index].length > destination.capacity || closed.get() || destination.closed.get()) return false;
                byte[] result = buffer[index];
                buffer[index] = null;
                tags[index] = null;
                destination.buffer[destination.tail] = result.clone()[0];
                destination.tags[destination.tail] = Arrays.copyOf(tags, tags.length)[0];
                destination.tail = (destination.tail + 1) % destination.capacity;
                destination.occupancy += result.length;
                head = (head + 1) % capacity;
                occupancy -= result.length;
                destination.lock.notifyAll();
                lock.notifyAll();
                return true;
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closed.set(true);
            lock.notifyAll();
        }
    }

    public Object snapshot() {
        synchronized (lock) {
            return new Object[] {buffer, tags, head, tail, occupancy, closed.get()};
        }
    }

    private boolean matches(String[] tags1, String tag2) {
        for (String tag : tags1) {
            if (tag.equals(tag2)) return true;
        }
        return false;
    }

    private void evict(int index) {
        buffer[index] = null;
        tags[index] = null;
        head = (index + 1) % capacity;
    }
}
