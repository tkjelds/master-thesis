import java.io.ByteArrayOutputStream;
import java.io.IOException;

// TaggedCapacityBuffer is a thread-safe buffer that stores messages.
@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private final byte[] buffer;
    private int head = 0;
    private int tail = 0;
    private int occupancy = 0;
    private boolean closed = false;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        buffer = new byte[capacity];
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        if (isClosed()) {
            throw new IllegalStateException("Buffer is closed");
        }
        if (payload == null || tags == null || evictionTags == null) {
            throw new IllegalArgumentException("Inputs cannot be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload cannot be empty");
        }
        if (payload.length > buffer.length) {
            throw new IllegalArgumentException("Payload too large for buffer");
        }

        while (true) {
            synchronized (this) {
                if (canFit(payload, evictionTags)) {
                    insert(payload, tags);
                    return nextId();
                } else {
                    evictOldest(evictionTags);
                }
            }
        }
    }

    public void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        if (isClosed()) {
            throw new IllegalStateException("Buffer is closed");
        }
        while (true) {
            synchronized (this) {
                if (canFit(payload, null)) {
                    insert(payload, tags);
                    return;
                } else {
                    wait();
                }
            }
        }
    }

    public byte[] takeMatching(String tag) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (isClosed() && !hasMatchingMessage(tag)) {
                    return null;
                } else if (hasMatchingMessage(tag)) {
                    return removeMatching(tag);
                } else {
                    wait();
                }
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (this == destination || isClosed() || destination.isClosed()) {
            return false;
        }
        synchronized (this) {
            synchronizeWith(destination);
            while (true) {
                Message msg = removeMatching(tag);
                if (msg == null) {
                    return false;
                } else if (!destination.canFit(msg.payload, null)) {
                    destination.evictOldest(null);
                } else {
                    insertDestination(msg);
                    return true;
                }
            }
        }
    }

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(head, tail, occupancy, closed);
        }
    }

    private boolean canFit(byte[] payload, String[] evictionTags) {
        return occupancy + payload.length <= buffer.length;
    }

    private void evictOldest(String[] evictionTags) {
        if (evictionTags == null || evictionTags.length == 0) {
            return;
        }
        for (int i = head; i < tail; i++) {
            Message msg = getMessage(i);
            if (matchesAny(msg.tags, evictionTags)) {
                removeAt(i);
                return;
            }
        }
    }

    private boolean hasMatchingMessage(String tag) {
        for (int i = head; i < tail; i++) {
            Message msg = getMessage(i);
            if (matches(msg.tags, tag)) {
                return true;
            }
        }
        return false;
    }

    private byte[] removeMatching(String tag) {
        for (int i = head; i < tail; i++) {
            Message msg = getMessage(i);
            if (matches(msg.tags, tag)) {
                return removeAt(i);
            }
        }
        return null;
    }

    private boolean matches(String[] tags, String tag) {
        for (String t : tags) {
            if (t.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAny(String[] tags, String[] evictionTags) {
        for (String t : tags) {
            for (String evictTag : evictionTags) {
                if (t.equals(evictTag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void insert(byte[] payload, String[] tags) {
        Message msg = new Message(nextId(), payload, tags);
        insertAt(tail, msg);
        tail++;
        occupancy += payload.length;
        notifyAll();
    }

    private byte[] removeAt(int index) {
        Message msg = getMessage(index);
        deleteAt(index);
        head++;
        occupancy -= msg.payload.length;
        return msg.payload;
    }

    private synchronized void synchronizeWith(TaggedCapacityBuffer destination) {
        // This method should be implemented to ensure deadlock-free two-buffer synchronization
        throw new UnsupportedOperationException("Not yet implemented");
    }

    private synchronized void insertDestination(Message msg) {
        // This method should be implemented to handle the insertion of a message into another buffer
        throw new UnsupportedOperationException("Not yet implemented");
    }

    private int nextId() {
        return (head + 1) & (buffer.length - 1);
    }

    private Message getMessage(int index) {
        return new Message(head + (index - head), buffer, 0);
    }

    private void deleteAt(int index) {
        for (int i = index; i < tail - 1; i++) {
            buffer[i] = buffer[i + 1];
        }
    }

    private boolean isClosed() {
        return closed;
    }

    public static class Snapshot {
        private final int head;
        private final int tail;
        private final int occupancy;
        private final boolean closed;

        public Snapshot(int head, int tail, int occupancy, boolean closed) {
            this.head = head;
            this.tail = tail;
            this.occupancy = occupancy;
            this.closed = closed;
        }

        // Getters for snapshot data
    }

    private static class Message {
        private final int id;
        private final byte[] payload;
        private final String[] tags;

        public Message(int id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload.clone();
            this.tags = tags.clone();
        }

        // Getters for message data
    }
}
