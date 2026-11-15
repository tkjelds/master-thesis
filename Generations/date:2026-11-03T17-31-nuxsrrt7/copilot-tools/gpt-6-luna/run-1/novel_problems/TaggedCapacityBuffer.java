@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1L;
    private static long nextBufferOrder = 1L;
    private static boolean messageIdsExhausted;
    private static boolean bufferOrdersExhausted;

    private final Object lock = new Object();
    private final int capacity;
    private final long bufferOrder;
    private Message[] messages = new Message[0];
    private int count;
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
        validatePayload(payload);
        String[] copiedTags = copyTags(tags, "tags");
        String[] copiedEvictionTags = copyTags(evictionTags, "evictionTags");
        byte[] copiedPayload = payload.clone();

        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            boolean[] evict = new boolean[count];
            long reclaimed = 0L;
            int evictedCount = 0;

            for (int i = 0; i < count
                    && (long) occupancy + copiedPayload.length - reclaimed > capacity; i++) {
                if (sharesAnyTag(messages[i].tags, copiedEvictionTags)) {
                    evict[i] = true;
                    reclaimed += messages[i].payload.length;
                    evictedCount++;
                }
            }

            for (int i = 0; i < count
                    && (long) occupancy + copiedPayload.length - reclaimed > capacity; i++) {
                if (!evict[i]) {
                    evict[i] = true;
                    reclaimed += messages[i].payload.length;
                    evictedCount++;
                }
            }

            int retainedCount = count - evictedCount;
            ensureMessageCapacity(retainedCount + 1);
            Message inserted = new Message(allocateMessageId(), copiedPayload, copiedTags);

            int write = 0;
            for (int i = 0; i < count; i++) {
                if (!evict[i]) {
                    messages[write++] = messages[i];
                }
            }
            for (int i = write; i < count; i++) {
                messages[i] = null;
            }
            count = write;
            messages[count++] = inserted;
            occupancy = (int) (occupancy - reclaimed + copiedPayload.length);
            lock.notifyAll();
            return inserted.id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        validatePayload(payload);
        String[] copiedTags = copyTags(tags, "tags");
        byte[] copiedPayload = payload.clone();

        synchronized (lock) {
            while (!closed && (long) occupancy + copiedPayload.length > capacity) {
                lock.wait();
            }
            if (closed) {
                throw new IllegalStateException("buffer is closed");
            }

            ensureMessageCapacity(count + 1);
            Message inserted = new Message(allocateMessageId(), copiedPayload, copiedTags);
            messages[count++] = inserted;
            occupancy += copiedPayload.length;
            lock.notifyAll();
            return inserted.id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }

        synchronized (lock) {
            while (true) {
                int index = findMatching(tag);
                if (index >= 0) {
                    Message detached = messages[index].detachedCopy();
                    removeAt(index);
                    lock.notifyAll();
                    return detached;
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
            throw new IllegalArgumentException("destination must not be null");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination must be a different buffer");
        }
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }

        TaggedCapacityBuffer first = bufferOrder < destination.bufferOrder ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;

        synchronized (first.lock) {
            synchronized (second.lock) {
                if (closed || destination.closed) {
                    return false;
                }

                int index = findMatching(tag);
                if (index < 0) {
                    return false;
                }

                Message moving = messages[index];
                if ((long) destination.occupancy + moving.payload.length > destination.capacity) {
                    return false;
                }

                destination.ensureMessageCapacity(destination.count + 1);
                removeAt(index);
                destination.messages[destination.count++] = moving;
                destination.occupancy += moving.payload.length;
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
            Message[] detached = new Message[count];
            for (int i = 0; i < count; i++) {
                detached[i] = messages[i].detachedCopy();
            }
            return new Snapshot(detached, occupancy, capacity, closed);
        }
    }

    private void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (payload.length > capacity) {
            throw new IllegalArgumentException("payload exceeds buffer capacity");
        }
    }

    private static String[] copyTags(String[] tags, String name) {
        if (tags == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        String[] copy = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            copy[i] = tags[i];
        }
        return copy;
    }

    private static boolean sharesAnyTag(String[] messageTags, String[] requestedTags) {
        for (int i = 0; i < messageTags.length; i++) {
            for (int j = 0; j < requestedTags.length; j++) {
                if (messageTags[i].equals(requestedTags[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    private int findMatching(String tag) {
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < messages[i].tags.length; j++) {
                if (messages[i].tags[j].equals(tag)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void removeAt(int index) {
        Message removed = messages[index];
        occupancy -= removed.payload.length;
        for (int i = index; i < count - 1; i++) {
            messages[i] = messages[i + 1];
        }
        messages[--count] = null;
    }

    private void ensureMessageCapacity(int required) {
        if (required <= messages.length) {
            return;
        }

        int newLength = messages.length == 0 ? 1 : messages.length;
        while (newLength < required) {
            if (newLength > Integer.MAX_VALUE / 2) {
                newLength = required;
                break;
            }
            newLength *= 2;
        }

        Message[] expanded = new Message[newLength];
        for (int i = 0; i < count; i++) {
            expanded[i] = messages[i];
        }
        messages = expanded;
    }

    private static long allocateMessageId() {
        synchronized (ID_LOCK) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("message ID space exhausted");
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
                throw new IllegalStateException("buffer order space exhausted");
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

        public byte[] payload() {
            return payload.clone();
        }

        public String[] tags() {
            return tags.clone();
        }

        private Message detachedCopy() {
            return new Message(id, payload.clone(), tags.clone());
        }
    }

    public static final class Snapshot {
        public final int occupancy;
        public final int capacity;
        public final boolean closed;
        private final Message[] messages;

        private Snapshot(Message[] messages, int occupancy, int capacity, boolean closed) {
            this.messages = messages;
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.closed = closed;
        }

        public Message[] messages() {
            return messages.clone();
        }

        public int messageCount() {
            return messages.length;
        }

        public Message messageAt(int index) {
            return messages[index];
        }
    }
}
