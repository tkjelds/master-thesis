@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextMessageId;
    private static long nextBufferOrder;

    private final Object monitor = new Object();
    private final int capacity;
    private final long bufferOrder;
    private Entry head;
    private Entry tail;
    private int occupancy;
    private boolean closed;

    private static final class Entry {
        final Message message;
        Entry next;

        Entry(Message message) {
            this.message = message;
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

        public long getId() {
            return id;
        }

        public byte[] getPayload() {
            return payload.clone();
        }

        public String[] getTags() {
            return tags.clone();
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

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        synchronized (ID_LOCK) {
            if (nextBufferOrder == Long.MAX_VALUE) {
                throw new IllegalStateException("Buffer order space exhausted");
            }
            this.bufferOrder = ++nextBufferOrder;
        }
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);
        String[] evictionTagsCopy = copyTags(evictionTags);

        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            long id = allocateMessageId();
            while (payloadCopy.length > capacity - occupancy) {
                Entry match = findOldestEvictionMatch(evictionTagsCopy);
                if (match != null) {
                    removeEntry(match);
                } else {
                    removeEntry(head);
                }
            }

            Message message = new Message(id, payloadCopy, tagsCopy);
            append(new Entry(message));
            occupancy += payloadCopy.length;
            monitor.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyTags(tags);

        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        synchronized (monitor) {
            while (!closed && payloadCopy.length > capacity - occupancy) {
                monitor.wait();
            }
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            long id = allocateMessageId();
            Message message = new Message(id, payloadCopy, tagsCopy);
            append(new Entry(message));
            occupancy += payloadCopy.length;
            monitor.notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new IllegalArgumentException("Tag must not be null");
        }

        synchronized (monitor) {
            while (true) {
                Entry match = findOldestMatchingTag(tag);
                if (match != null) {
                    Message message = match.message;
                    removeEntry(match);
                    occupancy -= message.payload.length;
                    monitor.notifyAll();
                    return message;
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
            throw new IllegalArgumentException("Destination must not be null");
        }
        if (destination == this) {
            throw new IllegalArgumentException("Cannot move to the same buffer");
        }
        if (tag == null) {
            throw new IllegalArgumentException("Tag must not be null");
        }

        TaggedCapacityBuffer first = bufferOrder < destination.bufferOrder ? this : destination;
        TaggedCapacityBuffer second = first == this ? destination : this;

        synchronized (first.monitor) {
            synchronized (second.monitor) {
                if (closed || destination.closed) {
                    return false;
                }

                Entry match = findOldestMatchingTag(tag);
                if (match == null) {
                    return false;
                }

                int messageLength = match.message.payload.length;
                if (messageLength > destination.capacity - destination.occupancy) {
                    return false;
                }

                removeEntry(match);
                occupancy -= messageLength;
                destination.append(new Entry(match.message));
                destination.occupancy += messageLength;

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
            int count = 0;
            for (Entry entry = head; entry != null; entry = entry.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Entry entry = head; entry != null; entry = entry.next) {
                messages[index++] = entry.message;
            }

            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private Entry findOldestMatchingTag(String tag) {
        for (Entry entry = head; entry != null; entry = entry.next) {
            if (hasTag(entry.message.tags, tag)) {
                return entry;
            }
        }
        return null;
    }

    private Entry findOldestEvictionMatch(String[] evictionTags) {
        for (Entry entry = head; entry != null; entry = entry.next) {
            for (int i = 0; i < evictionTags.length; i++) {
                if (hasTag(entry.message.tags, evictionTags[i])) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static boolean hasTag(String[] tags, String sought) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private void append(Entry entry) {
        if (tail == null) {
            head = entry;
            tail = entry;
        } else {
            tail.next = entry;
            tail = entry;
        }
    }

    private void removeEntry(Entry target) {
        Entry previous = null;
        Entry current = head;

        while (current != null && current != target) {
            previous = current;
            current = current.next;
        }

        if (current == null) {
            throw new IllegalStateException("Entry is not in this buffer");
        }

        if (previous == null) {
            head = current.next;
        } else {
            previous.next = current.next;
        }
        if (tail == current) {
            tail = previous;
        }
        current.next = null;
    }

    private static long allocateMessageId() {
        synchronized (ID_LOCK) {
            if (nextMessageId == Long.MAX_VALUE) {
                throw new IllegalStateException("Message ID space exhausted");
            }
            return ++nextMessageId;
        }
    }

    private static byte[] copyPayload(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload must not be empty");
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
                throw new IllegalArgumentException("Tag entries must not be null");
            }
        }
        return copy;
    }
}
