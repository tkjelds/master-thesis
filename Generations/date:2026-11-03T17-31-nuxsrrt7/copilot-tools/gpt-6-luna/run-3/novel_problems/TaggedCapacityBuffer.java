@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1L;
    private static boolean messageIdsExhausted;
    private static long nextBufferOrder = 1L;
    private static boolean bufferOrdersExhausted;

    private final int capacity;
    private final long bufferOrder;
    private Message[] messages = new Message[0];
    private int messageCount;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.bufferOrder = allocateBufferOrder();
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        PreparedMessage prepared = new PreparedMessage(payload, tags, capacity);
        String[] copiedEvictionTags = copyTags(evictionTags, "evictionTags");

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            Message message = new Message(allocateMessageId(), prepared.payload, prepared.tags);
            ensureMessageCapacity(messageCount + 1);

            long excess = (long) occupancy + message.payload.length - capacity;
            int priorityQuota = 0;
            int otherQuota = 0;
            long reclaimed = 0L;

            for (int i = 0; i < messageCount && reclaimed < excess; i++) {
                if (hasAnyTag(messages[i], copiedEvictionTags)) {
                    reclaimed += messages[i].payload.length;
                    priorityQuota++;
                }
            }

            for (int i = 0; i < messageCount && reclaimed < excess; i++) {
                if (!hasAnyTag(messages[i], copiedEvictionTags)) {
                    reclaimed += messages[i].payload.length;
                    otherQuota++;
                }
            }

            if (excess > 0L) {
                int removedPriority = 0;
                int removedOther = 0;
                int writeIndex = 0;
                int newOccupancy = occupancy;

                for (int i = 0; i < messageCount; i++) {
                    Message current = messages[i];
                    boolean priority = hasAnyTag(current, copiedEvictionTags);
                    boolean remove = priority
                            ? removedPriority < priorityQuota
                            : removedOther < otherQuota;

                    if (remove) {
                        if (priority) {
                            removedPriority++;
                        } else {
                            removedOther++;
                        }
                        newOccupancy -= current.payload.length;
                    } else {
                        messages[writeIndex++] = current;
                    }
                }

                for (int i = writeIndex; i < messageCount; i++) {
                    messages[i] = null;
                }
                messageCount = writeIndex;
                occupancy = newOccupancy;
            }

            append(message);
            notifyAll();
            return message.id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        PreparedMessage prepared = new PreparedMessage(payload, tags, capacity);

        synchronized (this) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("buffer is closed");
                }
                if (prepared.payload.length <= capacity - occupancy) {
                    break;
                }
                wait();
            }

            Message message = new Message(allocateMessageId(), prepared.payload, prepared.tags);
            ensureMessageCapacity(messageCount + 1);
            append(message);
            notifyAll();
            return message.id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        synchronized (this) {
            while (true) {
                int index = findMatchingIndex(tag);
                if (index >= 0) {
                    Message message = messages[index];
                    removeAt(index);
                    notifyAll();
                    return new Message(message.id, message.payload, message.tags);
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
            throw new IllegalArgumentException("destination must differ from source");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        if (bufferOrder < destination.bufferOrder) {
            synchronized (this) {
                synchronized (destination) {
                    return moveLocked(destination, tag);
                }
            }
        } else {
            synchronized (destination) {
                synchronized (this) {
                    return moveLocked(destination, tag);
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
            Message[] detached = new Message[messageCount];
            for (int i = 0; i < messageCount; i++) {
                Message message = messages[i];
                detached[i] = new Message(message.id, message.payload, message.tags);
            }
            return new Snapshot(detached, occupancy, capacity, closed);
        }
    }

    private boolean moveLocked(TaggedCapacityBuffer destination, String tag) {
        if (closed || destination.closed) {
            return false;
        }

        int index = findMatchingIndex(tag);
        if (index < 0) {
            return false;
        }

        Message message = messages[index];
        if (message.payload.length > destination.capacity - destination.occupancy) {
            return false;
        }

        destination.ensureMessageCapacity(destination.messageCount + 1);
        removeAt(index);
        destination.append(message);
        notifyAll();
        destination.notifyAll();
        return true;
    }

    private int findMatchingIndex(String tag) {
        for (int i = 0; i < messageCount; i++) {
            if (hasTag(messages[i], tag)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasTag(Message message, String tag) {
        for (int i = 0; i < message.tags.length; i++) {
            if (message.tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyTag(Message message, String[] tags) {
        for (int i = 0; i < message.tags.length; i++) {
            for (int j = 0; j < tags.length; j++) {
                if (message.tags[i].equals(tags[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    private void removeAt(int index) {
        Message removed = messages[index];
        int shifted = messageCount - index - 1;
        if (shifted > 0) {
            System.arraycopy(messages, index + 1, messages, index, shifted);
        }
        messages[--messageCount] = null;
        occupancy -= removed.payload.length;
    }

    private void append(Message message) {
        ensureMessageCapacity(messageCount + 1);
        messages[messageCount++] = message;
        occupancy += message.payload.length;
    }

    private void ensureMessageCapacity(int required) {
        if (required <= messages.length) {
            return;
        }

        int newLength = messages.length == 0 ? 1 : messages.length * 2;
        if (newLength < required || newLength < messages.length) {
            newLength = required;
        }

        Message[] expanded = new Message[newLength];
        System.arraycopy(messages, 0, expanded, 0, messageCount);
        messages = expanded;
    }

    private static String[] copyTags(String[] tags, String name) {
        if (tags == null) {
            throw new NullPointerException(name);
        }
        String[] copy = new String[tags.length];
        System.arraycopy(tags, 0, copy, 0, tags.length);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new NullPointerException(name + " contains null");
            }
        }
        return copy;
    }

    private static long allocateMessageId() {
        synchronized (ID_LOCK) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("message IDs exhausted");
            }
            long id = nextMessageId;
            if (id == Long.MAX_VALUE) {
                messageIdsExhausted = true;
            } else {
                nextMessageId++;
            }
            return id;
        }
    }

    private static long allocateBufferOrder() {
        synchronized (ID_LOCK) {
            if (bufferOrdersExhausted) {
                throw new IllegalStateException("buffer ordering IDs exhausted");
            }
            long order = nextBufferOrder;
            if (order == Long.MAX_VALUE) {
                bufferOrdersExhausted = true;
            } else {
                nextBufferOrder++;
            }
            return order;
        }
    }

    private static final class PreparedMessage {
        private final byte[] payload;
        private final String[] tags;

        private PreparedMessage(byte[] payload, String[] tags, int capacity) {
            if (payload == null) {
                throw new NullPointerException("payload");
            }
            if (payload.length == 0 || payload.length > capacity) {
                throw new IllegalArgumentException("payload length must be between 1 and capacity");
            }
            this.payload = new byte[payload.length];
            System.arraycopy(payload, 0, this.payload, 0, payload.length);
            this.tags = copyTags(tags, "tags");
        }
    }

    public static final class Message {
        private final long id;
        private final byte[] payload;
        private final String[] tags;

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = new byte[payload.length];
            System.arraycopy(payload, 0, this.payload, 0, payload.length);
            this.tags = new String[tags.length];
            System.arraycopy(tags, 0, this.tags, 0, tags.length);
        }

        public long getId() {
            return id;
        }

        public byte[] getPayload() {
            byte[] copy = new byte[payload.length];
            System.arraycopy(payload, 0, copy, 0, payload.length);
            return copy;
        }

        public String[] getTags() {
            String[] copy = new String[tags.length];
            System.arraycopy(tags, 0, copy, 0, tags.length);
            return copy;
        }
    }

    public static final class Snapshot {
        private final Message[] messages;
        private final int occupancy;
        private final int capacity;
        private final boolean closed;

        private Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
            this.messages = new Message[messages.length];
            System.arraycopy(messages, 0, this.messages, 0, messages.length);
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
            System.arraycopy(messages, 0, copy, 0, messages.length);
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
