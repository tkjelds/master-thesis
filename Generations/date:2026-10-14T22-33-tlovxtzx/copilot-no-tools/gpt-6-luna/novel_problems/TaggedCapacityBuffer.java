@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object TRANSFER_LOCK = new Object();
    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1L;
    private static boolean messageIdsExhausted;

    private final int capacity;
    private Message[] messages = new Message[0];
    private int messageCount;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyPayload(payload, capacity);
        String[] tagsCopy = copyTags(tags);
        String[] evictionTagsCopy = copyTags(evictionTags);

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            int requiredSlots = messageCount < capacity ? messageCount + 1 : messageCount;
            ensureMessageSlots(requiredSlots);
            long id = allocateMessageId();

            while (occupancy > capacity - payloadCopy.length) {
                int matchingIndex = findOldestMatchingEvictionTag(evictionTagsCopy);
                removeAt(matchingIndex >= 0 ? matchingIndex : 0);
            }

            append(new Message(id, payloadCopy, tagsCopy));
            occupancy += payloadCopy.length;
            notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload, capacity);
        String[] tagsCopy = copyTags(tags);

        synchronized (this) {
            while (!closed && occupancy > capacity - payloadCopy.length) {
                wait();
            }
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            ensureMessageSlots(messageCount + 1);
            long id = allocateMessageId();
            append(new Message(id, payloadCopy, tagsCopy));
            occupancy += payloadCopy.length;
            notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new IllegalArgumentException("Tag must not be null");
        }

        synchronized (this) {
            int index;
            while ((index = findOldestMatchingTag(tag)) < 0 && !closed) {
                wait();
            }
            if (index < 0) {
                return null;
            }

            Message result = detachedCopy(messages[index]);
            occupancy -= messages[index].payload.length;
            removeAt(index);
            notifyAll();
            return result;
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new IllegalArgumentException("Destination must not be null");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Destination must not be this buffer");
        }
        if (tag == null) {
            throw new IllegalArgumentException("Tag must not be null");
        }

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

                    Message message = messages[index];
                    int length = message.payload.length;
                    if (destination.occupancy > destination.capacity - length) {
                        return false;
                    }

                    destination.ensureMessageSlots(destination.messageCount + 1);
                    removeAt(index);
                    occupancy -= length;
                    destination.append(message);
                    destination.occupancy += length;

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
            Message[] detachedMessages = new Message[messageCount];
            for (int i = 0; i < messageCount; i++) {
                detachedMessages[i] = detachedCopy(messages[i]);
            }
            return new Snapshot(detachedMessages, occupancy, capacity, closed);
        }
    }

    private static byte[] copyPayload(byte[] payload, int capacity) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload must not be empty");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }
        return payload.clone();
    }

    private static String[] copyTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tags must not be null");
        }
        String[] copy = tags.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException("Tags must not contain null");
            }
        }
        return copy;
    }

    private static long allocateMessageId() {
        synchronized (ID_LOCK) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("Message ID space exhausted");
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

    private void ensureMessageSlots(int required) {
        if (required <= messages.length) {
            return;
        }

        int newLength = messages.length == 0 ? 4 : messages.length * 2;
        if (newLength < 0 || newLength > capacity) {
            newLength = capacity;
        }
        if (newLength < required) {
            newLength = required;
        }

        Message[] expanded = new Message[newLength];
        for (int i = 0; i < messageCount; i++) {
            expanded[i] = messages[i];
        }
        messages = expanded;
    }

    private void append(Message message) {
        messages[messageCount++] = message;
    }

    private void removeAt(int index) {
        for (int i = index; i < messageCount - 1; i++) {
            messages[i] = messages[i + 1];
        }
        messages[--messageCount] = null;
    }

    private int findOldestMatchingTag(String tag) {
        for (int i = 0; i < messageCount; i++) {
            if (hasTag(messages[i].tags, tag)) {
                return i;
            }
        }
        return -1;
    }

    private int findOldestMatchingEvictionTag(String[] evictionTags) {
        for (int i = 0; i < messageCount; i++) {
            for (int j = 0; j < evictionTags.length; j++) {
                if (hasTag(messages[i].tags, evictionTags[j])) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean hasTag(String[] tags, String sought) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private static Message detachedCopy(Message message) {
        return new Message(message.id, message.payload.clone(), message.tags.clone());
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

        public long id() {
            return id;
        }

        public long getId() {
            return id;
        }

        public byte[] payload() {
            return payload.clone();
        }

        public byte[] getPayload() {
            return payload();
        }

        public String[] tags() {
            return tags.clone();
        }

        public String[] getTags() {
            return tags();
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

        public Message[] messages() {
            return messages.clone();
        }

        public Message[] getMessages() {
            return messages();
        }

        public int occupancy() {
            return occupancy;
        }

        public int getOccupancy() {
            return occupancy;
        }

        public int capacity() {
            return capacity;
        }

        public int getCapacity() {
            return capacity;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
