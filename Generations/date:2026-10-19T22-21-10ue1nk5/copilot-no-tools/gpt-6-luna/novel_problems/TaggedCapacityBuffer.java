@TaggedCapacityBuffer.ThreadSafe
public final class TaggedCapacityBuffer {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextMessageId = 1;
    private static boolean messageIdsExhausted;

    private static final Object ORDER_LOCK = new Object();
    private static long nextLockOrder = 1;
    private static boolean lockOrdersExhausted;

    private final int capacity;
    private final long lockOrder;
    private int occupancy;
    private boolean closed;
    private Node head;
    private Node tail;

    public TaggedCapacityBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.lockOrder = allocateLockOrder();
    }

    public long offer(byte[] payload, String[] tags, String[] evictionTags) {
        byte[] payloadCopy = copyAndValidatePayload(payload);
        String[] tagsCopy = copyAndValidateTags(tags, "tags");
        String[] evictionTagsCopy = copyAndValidateTags(evictionTags, "evictionTags");

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            long id = allocateMessageId();
            Node newNode = new Node(id, payloadCopy, tagsCopy);

            evictMatching(evictionTagsCopy, payloadCopy.length);
            evictOldestUntilFits(payloadCopy.length);
            append(newNode);
            notifyAll();
            return id;
        }
    }

    public long putBlocking(byte[] payload, String[] tags) throws InterruptedException {
        byte[] payloadCopy = copyAndValidatePayload(payload);
        String[] tagsCopy = copyAndValidateTags(tags, "tags");

        synchronized (this) {
            while (!closed && capacity - occupancy < payloadCopy.length) {
                wait();
            }
            if (closed) {
                throw new IllegalStateException("Buffer is closed");
            }

            long id = allocateMessageId();
            append(new Node(id, payloadCopy, tagsCopy));
            notifyAll();
            return id;
        }
    }

    public Message takeMatching(String tag) throws InterruptedException {
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }

        synchronized (this) {
            Node previous;
            Node matching;
            while ((matching = findOldestMatching(tag)) == null) {
                if (closed) {
                    return null;
                }
                wait();
            }

            previous = findPrevious(matching);
            Message result = new Message(matching.id, matching.payload, matching.tags);
            unlink(previous, matching);
            notifyAll();
            return result;
        }
    }

    public boolean moveTo(TaggedCapacityBuffer destination, String tag) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        if (destination == this) {
            throw new IllegalArgumentException("destination must not be this buffer");
        }
        if (tag == null) {
            throw new IllegalArgumentException("tag must not be null");
        }

        if (lockOrder < destination.lockOrder) {
            synchronized (this) {
                synchronized (destination) {
                    return moveToLocked(destination, tag);
                }
            }
        } else {
            synchronized (destination) {
                synchronized (this) {
                    return moveToLocked(destination, tag);
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
            int count = 0;
            for (Node node = head; node != null; node = node.next) {
                count++;
            }

            Message[] messages = new Message[count];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messages[index++] = new Message(node.id, node.payload, node.tags);
            }
            return new Snapshot(messages, occupancy, capacity, closed);
        }
    }

    private boolean moveToLocked(TaggedCapacityBuffer destination, String tag) {
        if (closed || destination.closed) {
            return false;
        }

        Node previous = null;
        Node matching = head;
        while (matching != null && !hasTag(matching.tags, tag)) {
            previous = matching;
            matching = matching.next;
        }
        if (matching == null || destination.capacity - destination.occupancy < matching.payload.length) {
            return false;
        }

        Node moved = new Node(matching.id, matching.payload.clone(), matching.tags.clone());
        unlink(previous, matching);
        destination.append(moved);
        notifyAll();
        destination.notifyAll();
        return true;
    }

    private void evictMatching(String[] evictionTags, int requiredLength) {
        Node previous = null;
        Node current = head;
        while (current != null && capacity - occupancy < requiredLength) {
            Node next = current.next;
            if (sharesAnyTag(current.tags, evictionTags)) {
                unlink(previous, current);
            } else {
                previous = current;
            }
            current = next;
        }
    }

    private void evictOldestUntilFits(int requiredLength) {
        Node previous = null;
        Node current = head;
        while (current != null && capacity - occupancy < requiredLength) {
            Node next = current.next;
            unlink(previous, current);
            current = next;
        }
    }

    private void append(Node node) {
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        occupancy += node.payload.length;
    }

    private void unlink(Node previous, Node node) {
        if (previous == null) {
            head = node.next;
        } else {
            previous.next = node.next;
        }
        if (tail == node) {
            tail = previous;
        }
        occupancy -= node.payload.length;
        node.next = null;
    }

    private Node findOldestMatching(String tag) {
        for (Node node = head; node != null; node = node.next) {
            if (hasTag(node.tags, tag)) {
                return node;
            }
        }
        return null;
    }

    private Node findPrevious(Node target) {
        Node previous = null;
        for (Node node = head; node != target; node = node.next) {
            previous = node;
        }
        return previous;
    }

    private static boolean hasTag(String[] tags, String tag) {
        for (String candidate : tags) {
            if (candidate.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesAnyTag(String[] tags, String[] candidates) {
        for (String tag : tags) {
            for (String candidate : candidates) {
                if (tag.equals(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] copyAndValidatePayload(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        return payload.clone();
    }

    private static String[] copyAndValidateTags(String[] tags, String name) {
        if (tags == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        String[] copy = tags.clone();
        for (String tag : copy) {
            if (tag == null) {
                throw new IllegalArgumentException(name + " must not contain null values");
            }
        }
        return copy;
    }

    private static long allocateMessageId() {
        synchronized (ID_LOCK) {
            if (messageIdsExhausted) {
                throw new IllegalStateException("Message IDs exhausted");
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

    private static long allocateLockOrder() {
        synchronized (ORDER_LOCK) {
            if (lockOrdersExhausted) {
                throw new IllegalStateException("Buffer lock orders exhausted");
            }
            long order = nextLockOrder;
            if (order == Long.MAX_VALUE) {
                lockOrdersExhausted = true;
            } else {
                nextLockOrder++;
            }
            return order;
        }
    }

    private static final class Node {
        private final long id;
        private final byte[] payload;
        private final String[] tags;
        private Node next;

        private Node(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload;
            this.tags = tags;
        }
    }

    public static final class Message {
        public final long id;
        private final byte[] payload;
        private final String[] tags;

        private Message(long id, byte[] payload, String[] tags) {
            this.id = id;
            this.payload = payload.clone();
            this.tags = tags.clone();
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
                Message message = messages[i];
                copy[i] = new Message(message.id, message.payload, message.tags);
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
