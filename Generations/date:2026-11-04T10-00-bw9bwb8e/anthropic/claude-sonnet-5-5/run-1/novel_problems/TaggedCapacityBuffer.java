import java.lang.*;

@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}


    /** Immutable message; accessors return copies so callers get detached data. */
    public static final class Message {
        private final long id;
        private final byte[] payload;
        private final String[] tags;

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload;
            this.tags = tags;
        }

        public long getId() {
            return id;
        }

        public byte[] getPayload() {
            return payload.clone();
        }

        public String[] getTags() {
            return tags.clone();
        }

        public int size() {
            return payload.length;
        }

        private boolean hasTag(String tag) {
            for (int i = 0; i < tags.length; i++) {
                if (tags[i].equals(tag)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasAnyTag(String[] others) {
            for (int i = 0; i < others.length; i++) {
                if (hasTag(others[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Coherent detached view of the buffer. */
    public static final class Snapshot {
        private final Message[] messages;
        private final int occupancy;
        private final int capacity;
        private final boolean closed;

        private Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
            this.messages = messages;
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] getMessages() {
            return messages.clone();
        }

        public int getOccupancy() {
            return occupancy;
        }

        public int getCapacity() {
            return capacity;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1;
    private static long nextBufferId = 1;

    private static long newMessageId() {
        synchronized (ID_LOCK) {
            return nextMessageId++;
        }
    }

    private static long newBufferId() {
        synchronized (ID_LOCK) {
            return nextBufferId++;
        }
    }

    private final Object lock = new Object();
    private final long bufferId;
    private final int capacity;
    private Message[] items = new Message[8];
    private int count = 0;
    private int occupancy = 0;
    private boolean closed = false;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.bufferId = newBufferId();
    }

    private static String[] copyTags(String[] tags) {
        if (tags == null) {
            throw new NullPointerException("tags");
        }
        String[] copy = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new NullPointerException("tag element");
            }
            copy[i] = tags[i];
        }
        return copy;
    }

    private void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("payload larger than capacity");
        }
    }

    // All helpers below require the buffer's lock to be held.
    private void append(Message m) {
        if (count == items.length) {
            Message[] bigger = new Message[items.length * 2];
            System.arraycopy(items, 0, bigger, 0, count);
            items = bigger;
        }
        items[count++] = m;
        occupancy += m.payload.length;
    }

    private Message removeAt(int index) {
        Message m = items[index];
        System.arraycopy(items, index + 1, items, index, count - index - 1);
        items[--count] = null;
        occupancy -= m.payload.length;
        return m;
    }

    private int indexOfTag(String tag) {
        for (int i = 0; i < count; i++) {
            if (items[i].hasTag(tag)) {
                return i;
            }
        }
        return -1;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        validatePayload(payload);
        String[] tagCopy = copyTags(tags);
        String[] evictCopy = copyTags(evictionTags);
        byte[] payloadCopy = payload.clone();
        int len = payloadCopy.length;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("buffer closed");
            }
            boolean evicted = false;
            while (occupancy + len > capacity) {
                int idx = -1;
                for (int i = 0; i < count; i++) {
                    if (items[i].hasAnyTag(evictCopy)) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    break;
                }
                removeAt(idx);
                evicted = true;
            }
            while (occupancy + len > capacity && count > 0) {
                removeAt(0);
                evicted = true;
            }
            long id = newMessageId();
            append(new Message(id, payloadCopy, tagCopy));
            // space may have been freed for others, and a new message is available
            lock.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload);
        String[] tagCopy = copyTags(tags);
        byte[] payloadCopy = payload.clone();
        int len = payloadCopy.length;
        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("buffer closed");
                }
                if (occupancy + len <= capacity) {
                    break;
                }
                lock.wait();
            }
            long id = newMessageId();
            append(new Message(id, payloadCopy, tagCopy));
            lock.notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        synchronized (lock) {
            while (true) {
                int idx = indexOfTag(tag);
                if (idx >= 0) {
                    Message m = removeAt(idx);
                    lock.notifyAll();
                    return m;
                }
                if (closed) {
                    return null;
                }
                lock.wait();
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination must differ from source");
        }
        TaggedCapacityBuffer first = this.bufferId < destination.bufferId ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;
        synchronized (first.lock) {
            synchronized (second.lock) {
                if (closed || destination.closed) {
                    return false;
                }
                int idx = indexOfTag(tag);
                if (idx < 0) {
                    return false;
                }
                Message m = items[idx];
                if (destination.occupancy + m.payload.length > destination.capacity) {
                    return false;
                }
                removeAt(idx);
                destination.append(m);
                lock.notifyAll();
                destination.lock.notifyAll();
                return true;
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Message[] copy = new Message[count];
            System.arraycopy(items, 0, copy, 0, count);
            return new Snapshot(copy, occupancy, capacity, closed);
        }
    }
}
