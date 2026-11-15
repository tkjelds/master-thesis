@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static long nextBufferOrder;
    private static boolean bufferOrdersExhausted;
    private static long nextMessageId = 1;
    private static boolean messageIdsExhausted;

    private final Object monitor = new Object();
    private final int capacity;
    private final long bufferOrder;

    private Message[] messages = new Message[8];
    private int size;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        synchronized (TaggedCapacityBuffer.class) {
            if (bufferOrdersExhausted) {
                throw new IllegalStateException("Buffer order identifiers exhausted");
            }
            this.bufferOrder = nextBufferOrder;
            if (nextBufferOrder == Long.MAX_VALUE) {
                bufferOrdersExhausted = true;
            } else {
                nextBufferOrder++;
            }
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        validatePayload(payload, capacity);
        byte[] payloadCopy = copy(payload);
        String[] tagsCopy = copyAndValidateTags(tags);
        String[] evictionTagsCopy = copyAndValidateTags(evictionTags);

        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            ensureMessageSpace(size + 1);
            long id = allocateMessageId();

            while (payloadCopy.length > capacity - occupancy) {
                int index = findOldestWithAnyTag(evictionTagsCopy);
                if (index < 0) {
                    index = 0;
                }
                removeAt(index);
            }

            messages[size++] = new Message(id, payloadCopy, tagsCopy);
            occupancy += payloadCopy.length;
            monitor.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload, capacity);
        byte[] payloadCopy = copy(payload);
        String[] tagsCopy = copyAndValidateTags(tags);

        synchronized (monitor) {
            while (!closed && payloadCopy.length > capacity - occupancy) {
                monitor.wait();
            }
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            ensureMessageSpace(size + 1);
            long id = allocateMessageId();
            messages[size++] = new Message(id, payloadCopy, tagsCopy);
            occupancy += payloadCopy.length;
            monitor.notifyAll();
            return id;
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
                    Message message = messages[index];
                    removeAt(index);
                    monitor.notifyAll();
                    return copyMessage(message);
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
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Destination must be a different buffer");
        }

        TaggedCapacityBuffer first = this.bufferOrder < destination.bufferOrder
                ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;

        synchronized (first.monitor) {
            synchronized (second.monitor) {
                if (closed || destination.closed) {
                    return false;
                }

                int index = findOldestWithTag(tag);
                if (index < 0) {
                    return false;
                }

                Message message = messages[index];
                if (message.payload.length > destination.capacity - destination.occupancy) {
                    return false;
                }

                destination.ensureMessageSpace(destination.size + 1);
                removeAt(index);
                destination.messages[destination.size++] = message;
                destination.occupancy += message.payload.length;

                monitor.notifyAll();
                destination.monitor.notifyAll();
                return true;
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
            Message[] detached = new Message[size];
            for (int i = 0; i < size; i++) {
                detached[i] = copyMessage(messages[i]);
            }
            return new Snapshot(detached, occupancy, capacity, closed);
        }
    }

    private static void validatePayload(byte[] payload, int capacity) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload must not be empty");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }
    }

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] copyAndValidateTags(String[] source) {
        if (source == null) {
            throw new NullPointerException("tags");
        }
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            if (source[i] == null) {
                throw new NullPointerException("tag element");
            }
            result[i] = source[i];
        }
        return result;
    }

    private static long allocateMessageId() {
        synchronized (TaggedCapacityBuffer.class) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("Message identifiers exhausted");
            }
            long id = nextMessageId;
            if (nextMessageId == Long.MAX_VALUE) {
                messageIdsExhausted = true;
            } else {
                nextMessageId++;
            }
            return id;
        }
    }

    private static Message copyMessage(Message message) {
        return new Message(message.id, copy(message.payload), copy(message.tags));
    }

    private int findOldestWithTag(String tag) {
        for (int i = 0; i < size; i++) {
            if (hasTag(messages[i].tags, tag)) {
                return i;
            }
        }
        return -1;
    }

    private int findOldestWithAnyTag(String[] tags) {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < tags.length; j++) {
                if (hasTag(messages[i].tags, tags[j])) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean hasTag(String[] tags, String target) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(target)) {
                return true;
            }
        }
        return false;
    }

    private void ensureMessageSpace(int required) {
        if (required <= messages.length) {
            return;
        }
        int newLength = messages.length;
        while (newLength < required) {
            if (newLength > Integer.MAX_VALUE / 2) {
                newLength = required;
                break;
            }
            newLength *= 2;
        }
        Message[] expanded = new Message[newLength];
        for (int i = 0; i < size; i++) {
            expanded[i] = messages[i];
        }
        messages = expanded;
    }

    private void removeAt(int index) {
        Message removed = messages[index];
        occupancy -= removed.payload.length;
        for (int i = index; i < size - 1; i++) {
            messages[i] = messages[i + 1];
        }
        messages[--size] = null;
    }

    public static final class Message {
        public final long id;
        public final byte[] payload;
        public final String[] tags;

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload;
            this.tags = tags;
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
    }
}
