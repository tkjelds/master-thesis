@TaggedCapacityBuffer.ThreadSafe
public class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object MOVE_LOCK = new Object();

    private final Object monitor = new Object();
    private final int capacity;
    private Message[] messages = new Message[8];
    private int messageCount;
    private int occupancy;
    private boolean closed;
    private long nextId = 1L;
    private boolean idsExhausted;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        validatePayload(payload);
        validateTags(tags, "tags");
        validateTags(evictionTags, "evictionTags");
        if (payload.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        byte[] payloadCopy = payload.clone();
        String[] tagsCopy = tags.clone();
        String[] evictionTagsCopy = evictionTags.clone();

        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }
            ensureIdAvailable();
            ensureRoomForOne();

            Message message = new Message(nextId, payloadCopy, tagsCopy);
            while (occupancy > capacity - payloadCopy.length) {
                int index = findOldestWithAnyTag(evictionTagsCopy);
                if (index < 0) {
                    index = 0;
                }
                removeAt(index);
            }

            long id = message.id;
            append(message);
            consumeId();
            monitor.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload);
        validateTags(tags, "tags");
        if (payload.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        byte[] payloadCopy = payload.clone();
        String[] tagsCopy = tags.clone();

        synchronized (monitor) {
            ensureIdAvailable();
            while (!closed && occupancy > capacity - payloadCopy.length) {
                monitor.wait();
                ensureIdAvailable();
            }
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            ensureRoomForOne();
            Message message = new Message(nextId, payloadCopy, tagsCopy);
            append(message);
            consumeId();
            monitor.notifyAll();
            return message.id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        synchronized (monitor) {
            while (true) {
                int index = findOldestWithTag(tag);
                if (index >= 0) {
                    Message result = messages[index].detachedCopy();
                    removeAt(index);
                    monitor.notifyAll();
                    return result;
                }
                if (closed) {
                    return null;
                }
                monitor.wait();
            }
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Cannot move to the same buffer");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        synchronized (MOVE_LOCK) {
            synchronized (monitor) {
                synchronized (destination.monitor) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    int index = findOldestWithTag(tag);
                    if (index < 0) {
                        return false;
                    }

                    Message message = messages[index];
                    int length = message.payload.length;
                    if (length > destination.capacity - destination.occupancy) {
                        return false;
                    }

                    destination.ensureRoomForOne();
                    removeAt(index);
                    destination.append(message);
                    monitor.notifyAll();
                    destination.monitor.notifyAll();
                    return true;
                }
            }
        }
    }

    public void close() {
        synchronized (monitor) {
            if (!closed) {
                closed = true;
                monitor.notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            Message[] detached = new Message[messageCount];
            for (int i = 0; i < messageCount; i++) {
                detached[i] = messages[i].detachedCopy();
            }
            return new Snapshot(detached, occupancy, capacity, closed);
        }
    }

    private static void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload must not be empty");
        }
    }

    private static void validateTags(String[] tags, String name) {
        if (tags == null) {
            throw new NullPointerException(name);
        }
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new NullPointerException(name + " contains a null tag");
            }
        }
    }

    private void ensureIdAvailable() {
        if (idsExhausted) {
            throw new IllegalStateException("No message IDs remain");
        }
    }

    private void consumeId() {
        if (nextId == Long.MAX_VALUE) {
            idsExhausted = true;
        } else {
            nextId++;
        }
    }

    private void ensureRoomForOne() {
        if (messageCount < messages.length) {
            return;
        }
        if (messageCount == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Too many messages");
        }

        int newLength;
        if (messages.length == 0) {
            newLength = 1;
        } else if (messages.length <= Integer.MAX_VALUE / 2) {
            newLength = messages.length * 2;
        } else {
            newLength = Integer.MAX_VALUE;
        }
        if (newLength <= messageCount) {
            throw new OutOfMemoryError("Too many messages");
        }

        Message[] expanded = new Message[newLength];
        System.arraycopy(messages, 0, expanded, 0, messageCount);
        messages = expanded;
    }

    private void append(Message message) {
        messages[messageCount++] = message;
        occupancy += message.payload.length;
    }

    private void removeAt(int index) {
        occupancy -= messages[index].payload.length;
        int remaining = messageCount - index - 1;
        if (remaining > 0) {
            System.arraycopy(messages, index + 1, messages, index, remaining);
        }
        messages[--messageCount] = null;
    }

    private int findOldestWithTag(String tag) {
        for (int i = 0; i < messageCount; i++) {
            if (messages[i].hasTag(tag)) {
                return i;
            }
        }
        return -1;
    }

    private int findOldestWithAnyTag(String[] tags) {
        for (int i = 0; i < messageCount; i++) {
            if (messages[i].hasAnyTag(tags)) {
                return i;
            }
        }
        return -1;
    }

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

        private boolean hasTag(String tag) {
            for (int i = 0; i < tags.length; i++) {
                if (tags[i].equals(tag)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasAnyTag(String[] candidates) {
            for (int i = 0; i < tags.length; i++) {
                for (int j = 0; j < candidates.length; j++) {
                    if (tags[i].equals(candidates[j])) {
                        return true;
                    }
                }
            }
            return false;
        }

        private Message detachedCopy() {
            return new Message(id, payload.clone(), tags.clone());
        }
    }

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

        public int size() {
            return messages.length;
        }

        public Message getMessage(int index) {
            return messages[index].detachedCopy();
        }

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copy[i] = messages[i].detachedCopy();
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
