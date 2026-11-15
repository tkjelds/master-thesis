@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object LOCK_ORDER_MONITOR = new Object();
    private static final Object MESSAGE_ID_MONITOR = new Object();
    private static long nextLockOrder;
    private static long nextMessageId = 1L;
    private static boolean messageIdsExhausted;

    private final int capacity;
    private final long lockOrder;
    private Message[] messages = new Message[8];
    private int size;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        synchronized (LOCK_ORDER_MONITOR) {
            if (nextLockOrder == Long.MAX_VALUE) {
                throw new IllegalStateException("buffer lock order exhausted");
            }
            this.lockOrder = nextLockOrder++;
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        validatePayload(payload);
        String[] payloadTags = copyAndValidateTags(tags, "tags");
        String[] priorityTags = copyAndValidateTags(evictionTags, "evictionTags");
        byte[] payloadCopy = copyBytes(payload);
        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("payload exceeds buffer capacity");
        }

        synchronized (this) {
            ensureOpen();
            long id = allocateMessageId();
            int index = 0;
            while (index < size && (long) occupancy + payloadCopy.length > capacity) {
                if (sharesAnyTag(messages[index], priorityTags)) {
                    removeAt(index);
                } else {
                    index++;
                }
            }
            while ((long) occupancy + payloadCopy.length > capacity) {
                removeAt(0);
            }

            append(new Message(id, payloadCopy, payloadTags));
            notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload);
        String[] payloadTags = copyAndValidateTags(tags, "tags");
        byte[] payloadCopy = copyBytes(payload);
        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("payload exceeds buffer capacity");
        }

        synchronized (this) {
            ensureOpen();
            while ((long) occupancy + payloadCopy.length > capacity) {
                wait();
                ensureOpen();
            }
            ensureOpen();
            long id = allocateMessageId();
            append(new Message(id, payloadCopy, payloadTags));
            notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new NullPointerException("tag");
        }
        synchronized (this) {
            while (true) {
                int index = oldestMatchingIndex(tag);
                if (index >= 0) {
                    Message result = messages[index].detachedCopy();
                    removeAt(index);
                    notifyAll();
                    return result;
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
            throw new IllegalArgumentException("destination must be a different buffer");
        }
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        if (lockOrder < destination.lockOrder) {
            synchronized (this) {
                synchronized (destination) {
                    return moveLocked(destination, tag);
                }
            }
        }
        synchronized (destination) {
            synchronized (this) {
                return moveLocked(destination, tag);
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
            Message[] detached = new Message[size];
            for (int i = 0; i < size; i++) {
                detached[i] = messages[i].detachedCopy();
            }
            return new Snapshot(detached, occupancy, capacity, closed);
        }
    }

    private boolean moveLocked(TaggedCapacityBuffer destination, String tag) {
        if (closed || destination.closed) {
            return false;
        }
        int index = oldestMatchingIndex(tag);
        if (index < 0) {
            return false;
        }
        Message message = messages[index];
        if ((long) destination.occupancy + message.payload.length > destination.capacity) {
            return false;
        }

        removeAt(index);
        destination.append(message);
        notifyAll();
        destination.notifyAll();
        return true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("buffer is closed");
        }
    }

    private int oldestMatchingIndex(String tag) {
        for (int i = 0; i < size; i++) {
            if (containsTag(messages[i].tags, tag)) {
                return i;
            }
        }
        return -1;
    }

    private void append(Message message) {
        ensureStorage();
        messages[size++] = message;
        occupancy += message.payload.length;
    }

    private void removeAt(int index) {
        Message removed = messages[index];
        for (int i = index; i < size - 1; i++) {
            messages[i] = messages[i + 1];
        }
        messages[--size] = null;
        occupancy -= removed.payload.length;
    }

    private void ensureStorage() {
        if (size < messages.length) {
            return;
        }
        int newLength = messages.length * 2;
        if (newLength <= messages.length) {
            newLength = Integer.MAX_VALUE;
        }
        Message[] expanded = new Message[newLength];
        for (int i = 0; i < size; i++) {
            expanded[i] = messages[i];
        }
        messages = expanded;
    }

    private static boolean sharesAnyTag(Message message, String[] soughtTags) {
        for (int i = 0; i < soughtTags.length; i++) {
            if (containsTag(message.tags, soughtTags[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTag(String[] tags, String soughtTag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(soughtTag)) {
                return true;
            }
        }
        return false;
    }

    private static void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
    }

    private static String[] copyAndValidateTags(String[] tags, String name) {
        if (tags == null) {
            throw new NullPointerException(name);
        }
        String[] copy = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new NullPointerException(name + "[" + i + "]");
            }
            copy[i] = tags[i];
        }
        return copy;
    }

    private static byte[] copyBytes(byte[] bytes) {
        byte[] copy = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            copy[i] = bytes[i];
        }
        return copy;
    }

    private static long allocateMessageId() {
        synchronized (MESSAGE_ID_MONITOR) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("message ID space exhausted");
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

    public static final class Message {
        public final long id;
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
            return copyBytes(payload);
        }

        public String[] getTags() {
            return copyAndValidateTags(tags, "tags");
        }

        private Message detachedCopy() {
            return new Message(id, copyBytes(payload), copyAndValidateTags(tags, "tags"));
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
