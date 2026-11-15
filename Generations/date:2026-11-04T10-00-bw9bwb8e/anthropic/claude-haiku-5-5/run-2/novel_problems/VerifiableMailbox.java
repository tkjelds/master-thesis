@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}


    public static final class Message {
        private final long id;
        private final boolean draft;
        private final byte[] content;
        private final String validator;

        private Message(long id, boolean draft, byte[] content, String validator) {
            this.id = id;
            this.draft = draft;
            this.content = content;
            this.validator = validator;
        }

        public long getId() {
            return id;
        }

        public boolean isDraft() {
            return draft;
        }

        public byte[] getContent() {
            return content.clone();
        }

        public String getValidator() {
            return validator;
        }
    }

    public static final class Header {
        private final int capacity;
        private final long occupancy;
        private final long[] messageIds;
        private final String[] strongValidators;
        private final String[] weakValidators;

        private Header(int capacity, long occupancy, long[] messageIds,
                       String[] strongValidators, String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
            this.weakValidators = weakValidators;
        }

        public int getCapacity() {
            return capacity;
        }

        public long getOccupancy() {
            return occupancy;
        }

        public long[] getMessageIds() {
            return messageIds.clone();
        }

        public String[] getStrongValidators() {
            return strongValidators.clone();
        }

        public String[] getWeakValidators() {
            return weakValidators.clone();
        }
    }

    private static final class Node {
        final long id;
        final boolean draft;
        final byte[] content;
        final String validator;
        Node next;

        Node(long id, boolean draft, byte[] content, String validator) {
            this.id = id;
            this.draft = draft;
            this.content = content;
            this.validator = validator;
        }
    }

    private final int capacity;
    private final Object lock = new Object();

    // Guarded by lock. FIFO inbox: head is oldest, tail is newest.
    private Node head;
    private Node tail;
    private int count;
    private long occupancy;

    // Guarded by lock. IDs are positive, strictly increasing, never reused.
    private long nextId = 1L;
    private boolean idsExhausted = false;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    // Returns the assigned ID, or -1 if content duplicates a currently stored message.
    // Never waits; evicts oldest messages as needed.
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must be non-null and non-empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("content exceeds total capacity");
        }
        byte[] copy = content.clone();

        synchronized (lock) {
            if (containsDuplicate(copy)) {
                return -1L;
            }
            if (idsExhausted) {
                throw new IllegalStateException("message ID space exhausted");
            }

            long length = copy.length;
            while (occupancy + length > capacity) {
                removeHead();
            }

            long id = nextId;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = id + 1L;
            }

            String validator = (draft ? "W-" : "S-") + id;
            Node node = new Node(id, draft, copy, validator);
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            count++;
            occupancy += length;
            return id;
        }
    }

    // Returns a detached copy if id and exact validator match a stored message; otherwise null.
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("messageId must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must be non-null");
        }
        synchronized (lock) {
            for (Node n = head; n != null; n = n.next) {
                if (n.id == messageId) {
                    if (n.validator.equals(validator)) {
                        return new Message(n.id, n.draft, n.content.clone(), n.validator);
                    }
                    return null;
                }
                if (n.id > messageId) {
                    return null;
                }
            }
            return null;
        }
    }

    // Removes all messages with ID <= throughId; returns number removed.
    public int prune(long throughId) {
        if (throughId < 0L) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }
        synchronized (lock) {
            int removed = 0;
            while (head != null && head.id <= throughId) {
                removeHead();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[count];
            String[] strong = new String[count];
            String[] weak = new String[count];
            int strongCount = 0;
            int weakCount = 0;
            int i = 0;
            for (Node n = head; n != null; n = n.next) {
                ids[i++] = n.id;
                if (n.draft) {
                    weak[weakCount++] = n.validator;
                } else {
                    strong[strongCount++] = n.validator;
                }
            }
            String[] strongOut = new String[strongCount];
            String[] weakOut = new String[weakCount];
            for (int j = 0; j < strongCount; j++) {
                strongOut[j] = strong[j];
            }
            for (int j = 0; j < weakCount; j++) {
                weakOut[j] = weak[j];
            }
            return new Header(capacity, occupancy, ids, strongOut, weakOut);
        }
    }

    // Must be called while holding lock.
    private boolean containsDuplicate(byte[] content) {
        for (Node n = head; n != null; n = n.next) {
            if (n.content.length == content.length && sameBytes(n.content, content)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameBytes(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    // Must be called while holding lock and with head != null.
    private void removeHead() {
        Node old = head;
        head = old.next;
        if (head == null) {
            tail = null;
        }
        old.next = null;
        count--;
        occupancy -= old.content.length;
    }
}
