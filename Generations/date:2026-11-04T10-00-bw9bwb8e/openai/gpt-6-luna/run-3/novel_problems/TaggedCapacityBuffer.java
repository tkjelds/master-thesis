@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object TRANSFER_LOCK = new Object();
    private static long nextMessageId = 1L;

    private final int capacity;
    private Message[] entries = new Message[0];
    private int count;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = validateAndCopyPayload(payload);
        String[] tagsCopy = validateAndCopyTags(tags, "tags");
        String[] evictionTagsCopy = validateAndCopyTags(evictionTags, "evictionTags");

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            long id = allocateMessageId();
            while ((long) occupancy + payloadCopy.length > capacity) {
                int index = findOldestMatching(evictionTagsCopy);
                if (index < 0) {
                    index = 0;
                }
                removeAt(index);
            }

            ensureEntryCapacity(count + 1);
            entries[count++] = new Message(id, payloadCopy, tagsCopy);
            occupancy += payloadCopy.length;
            notifyAll();
            return id;
        }
    }

    public void putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = validateAndCopyPayload(payload);
        String[] tagsCopy = validateAndCopyTags(tags, "tags");

        synchronized (this) {
            while (!closed && (long) occupancy + payloadCopy.length > capacity) {
                wait();
            }
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            long id = allocateMessageId();
            ensureEntryCapacity(count + 1);
            entries[count++] = new Message(id, payloadCopy, tagsCopy);
            occupancy += payloadCopy.length;
            notifyAll();
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        synchronized (this) {
            while (true) {
                int index = findOldestMatchingTag(tag);
                if (index >= 0) {
                    Message message = entries[index];
                    removeAt(index);
                    notifyAll();
                    return new Message(message);
                }
                if (closed) {
                    return null;
                }
                wait();
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Destination must be a different buffer");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        // Serializing transfers before acquiring either buffer monitor prevents
        // opposite-direction moves from acquiring the monitors in reverse order.
        synchronized (TRANSFER_LOCK) {
            synchronized (this) {
                synchronized (destination) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    int index = findOldestMatchingTag(tag);
                    if (index < 0) {
                        return false;
                    }

                    Message message = entries[index];
                    if ((long) destination.occupancy + message.payload.length
                            > destination.capacity) {
                        return false;
                    }

                    destination.ensureEntryCapacity(destination.count + 1);
                    removeAt(index);
                    destination.entries[destination.count++] = message;
                    destination.occupancy += message.payload.length;

                    notifyAll();
                    destination.notifyAll();
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
            Message[] detached = new Message[count];
            for (int i = 0; i < count; i++) {
                detached[i] = new Message(entries[i]);
            }
            return new Snapshot(detached, occupancy, capacity, closed);
        }
    }

    private byte[] validateAndCopyPayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload must not be empty");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }
        return payload.clone();
    }

    private static String[] validateAndCopyTags(String[] tags, String name) {
        if (tags == null) {
            throw new NullPointerException(name);
        }
        String[] copy = tags.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new NullPointerException(name + " contains a null tag");
            }
        }
        return copy;
    }

    private long allocateMessageId() {
        synchronized (TaggedCapacityBuffer.class) {
            if (nextMessageId <= 0L) {
                throw new IllegalStateException("Message ID space exhausted");
            }
            return nextMessageId++;
        }
    }

    private int findOldestMatching(String[] tags) {
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < entries[i].tags.length; j++) {
                for (int k = 0; k < tags.length; k++) {
                    if (entries[i].tags[j].equals(tags[k])) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private int findOldestMatchingTag(String tag) {
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < entries[i].tags.length; j++) {
                if (entries[i].tags[j].equals(tag)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void removeAt(int index) {
        occupancy -= entries[index].payload.length;
        int remaining = count - index - 1;
        if (remaining > 0) {
            System.arraycopy(entries, index + 1, entries, index, remaining);
        }
        entries[--count] = null;
    }

    private void ensureEntryCapacity(int required) {
        if (required <= entries.length) {
            return;
        }
        int newLength;
        if (entries.length == 0) {
            newLength = 4;
        } else if (entries.length <= Integer.MAX_VALUE / 2) {
            newLength = entries.length * 2;
        } else {
            newLength = Integer.MAX_VALUE;
        }
        if (newLength < required) {
            newLength = required;
        }
        Message[] expanded = new Message[newLength];
        System.arraycopy(entries, 0, expanded, 0, count);
        entries = expanded;
    }

    public static final class Message {
        public final long id;
        public final byte[] payload;
        public final String[] tags;

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload.clone();
            this.tags = tags.clone();
        }

        private Message(Message source) {
            this(source.id, source.payload, source.tags);
        }

        public byte[] getPayload() {
            return payload.clone();
        }

        public String[] getTags() {
            return tags.clone();
        }
    }

    public static final class Snapshot {
        public final Message[] messages;
        public final int occupancy;
        public final int capacity;
        public final boolean closed;

        private Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
            this.messages = messages;
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copy[i] = new Message(messages[i]);
            }
            return copy;
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
}
