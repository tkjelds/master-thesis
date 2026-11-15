@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static final Object TRANSFER_LOCK = new Object();

    private static long nextMessageId = 1;
    private static boolean messageIdsExhausted;

    private final Object lock = new Object();
    private final int capacity;
    private Node head;
    private Node tail;
    private int occupancy;
    private boolean closed;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyAndValidateTags(tags, "tags");
        String[] evictionTagsCopy = copyAndValidateTags(evictionTags, "evictionTags");

        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            long id = allocateMessageId();
            Node previous = null;
            Node current = head;

            while (current != null && payloadCopy.length > capacity - occupancy) {
                Node next = current.next;
                if (matchesAny(current.message.tags, evictionTagsCopy)) {
                    removeNode(previous, current);
                } else {
                    previous = current;
                }
                current = next;
            }

            while (payloadCopy.length > capacity - occupancy) {
                removeNode(null, head);
            }

            appendNode(new Node(new Message(id, payloadCopy, tagsCopy)));
            occupancy += payloadCopy.length;
            lock.notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyPayload(payload);
        String[] tagsCopy = copyAndValidateTags(tags, "tags");

        if (payloadCopy.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds buffer capacity");
        }

        synchronized (lock) {
            while (!closed && payloadCopy.length > capacity - occupancy) {
                lock.wait();
            }
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            long id = allocateMessageId();
            appendNode(new Node(new Message(id, payloadCopy, tagsCopy)));
            occupancy += payloadCopy.length;
            lock.notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        requireTag(tag);

        synchronized (lock) {
            while (true) {
                Node previous = null;
                Node current = head;

                while (current != null) {
                    if (containsTag(current.message.tags, tag)) {
                        removeNode(previous, current);
                        occupancy -= current.message.payload.length;
                        Message result = current.message.copyMessage();
                        lock.notifyAll();
                        return result;
                    }
                    previous = current;
                    current = current.next;
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
        if (destination == this) {
            throw new IllegalArgumentException("Destination must be a different buffer");
        }
        requireTag(tag);

        synchronized (TRANSFER_LOCK) {
            synchronized (lock) {
                synchronized (destination.lock) {
                    if (closed || destination.closed) {
                        return false;
                    }

                    Node previous = null;
                    Node current = head;
                    while (current != null && !containsTag(current.message.tags, tag)) {
                        previous = current;
                        current = current.next;
                    }

                    if (current == null
                            || current.message.payload.length > destination.capacity - destination.occupancy) {
                        return false;
                    }

                    removeNode(previous, current);
                    occupancy -= current.message.payload.length;
                    destination.appendNode(current);
                    destination.occupancy += current.message.payload.length;

                    lock.notifyAll();
                    destination.lock.notifyAll();
                    return true;
                }
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
            int count = 0;
            for (Node node = head; node != null; node = node.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messages[index++] = node.message.copyMessage();
            }

            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private void appendNode(Node node) {
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
    }

    private void removeNode(Node previous, Node node) {
        if (previous == null) {
            head = node.next;
        } else {
            previous.next = node.next;
        }
        if (tail == node) {
            tail = previous;
        }
        node.next = null;
        occupancy -= node.message.payload.length;
    }

    private static long allocateMessageId() {
        synchronized (ID_LOCK) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("Message ID space exhausted");
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

    private static byte[] copyPayload(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("Payload must not be empty");
        }
        byte[] copy = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            copy[i] = payload[i];
        }
        return copy;
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

    private static void requireTag(String tag) {
        if (tag == null) {
            throw new NullPointerException("tag");
        }
    }

    private static boolean containsTag(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String[] messageTags, String[] soughtTags) {
        for (int i = 0; i < messageTags.length; i++) {
            for (int j = 0; j < soughtTags.length; j++) {
                if (messageTags[i].equals(soughtTags[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static final class Node {
        private final Message message;
        private Node next;

        private Node(Message message) {
            this.message = message;
        }
    }

    public static final class Message {
        public final long id;
        private final byte[] payload;
        private final String[] tags;

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = copyBytes(payload);
            this.tags = copyStrings(tags);
        }

        public byte[] getPayload() {
            return copyBytes(payload);
        }

        public String[] getTags() {
            return copyStrings(tags);
        }

        private Message copyMessage() {
            return new Message(id, payload, tags);
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

        public int size() {
            return messages.length;
        }

        public Message getMessage(int index) {
            return messages[index].copyMessage();
        }

        public Message[] getMessages() {
            Message[] copies = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copies[i] = messages[i].copyMessage();
            }
            return copies;
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
