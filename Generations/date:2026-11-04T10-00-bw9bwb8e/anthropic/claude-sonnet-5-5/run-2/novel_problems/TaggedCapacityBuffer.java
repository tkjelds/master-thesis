import java.io.Serializable;

@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}


    // ---- global (static) helpers: leaf locks only ----
    private static final Object GLOBAL_LOCK = new Object();
    private static long nextMessageId = 1;
    private static long nextBufferId = 1;

    private static long newMessageId() {
        synchronized (GLOBAL_LOCK) {
            return nextMessageId++;
        }
    }

    private static long newBufferId() {
        synchronized (GLOBAL_LOCK) {
            return nextBufferId++;
        }
    }

    /** Immutable message; accessors return detached copies. */
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

        private boolean sharesAny(String[] other) {
            for (int i = 0; i < other.length; i++) {
                if (hasTag(other[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Coherent immutable view of the buffer. */
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

    // ---- instance state, guarded by lock ----
    private final Object lock = new Object();
    private final long bufferId = newBufferId();
    private final int capacity;
    private Message[] items = new Message[16];
    private int count = 0;
    private int occupancy = 0;
    private boolean closed = false;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    private static String[] copyTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("tags must not be null");
        }
        String[] c = tags.clone();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == null) {
                throw new IllegalArgumentException("null tag");
            }
        }
        return c;
    }

    private byte[] validatePayload(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("payload exceeds capacity");
        }
        return payload.clone();
    }

    // must hold lock
    private void removeAt(int index) {
        Message m = items[index];
        for (int i = index; i < count - 1; i++) {
            items[i] = items[i + 1];
        }
        items[--count] = null;
        occupancy -= m.payload.length;
    }

    // must hold lock
    private void append(Message m) {
        if (count == items.length) {
            Message[] n = new Message[items.length * 2];
            for (int i = 0; i < count; i++) {
                n[i] = items[i];
            }
            items = n;
        }
        items[count++] = m;
        occupancy += m.payload.length;
    }

    // must hold lock
    private int findFirst(String tag) {
        for (int i = 0; i < count; i++) {
            if (items[i].hasTag(tag)) {
                return i;
            }
        }
        return -1;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] p = validatePayload(payload);
        String[] t = copyTags(tags);
        String[] e = copyTags(evictionTags);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("buffer closed");
            }
            int need = p.length;
            // first: oldest messages sharing an eviction tag
            int i = 0;
            while (capacity - occupancy < need && i < count) {
                if (items[i].sharesAny(e)) {
                    removeAt(i);
                } else {
                    i++;
                }
            }
            // then: remaining oldest
            while (capacity - occupancy < need && count > 0) {
                removeAt(0);
            }
            Message m = new Message(newMessageId(), p, t);
            append(m);
            lock.notifyAll();
            return m.id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] p = validatePayload(payload);
        String[] t = copyTags(tags);
        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("buffer closed");
                }
                if (capacity - occupancy >= p.length) {
                    break;
                }
                lock.wait();
            }
            Message m = new Message(newMessageId(), p, t);
            append(m);
            lock.notifyAll();
            return m.id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }
        synchronized (lock) {
            while (true) {
                int idx = findFirst(tag);
                if (idx >= 0) {
                    Message m = items[idx];
                    removeAt(idx);
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
        if (destination == null || tag == null) {
            throw new IllegalArgumentException("null argument");
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
                int idx = findFirst(tag);
                if (idx < 0) {
                    return false;
                }
                Message m = items[idx];
                if (destination.capacity - destination.occupancy < m.payload.length) {
                    return false;
                }
                removeAt(idx);
                destination.append(m);
                this.lock.notifyAll();
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
            for (int i = 0; i < count; i++) {
                copy[i] = items[i];
            }
            return new Snapshot(copy, occupancy, capacity, closed);
        }
    }
}
