@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private static final long MAX_ID = 9223372036854775807L;

    private final Object lock = new Object();
    private final int capacity;

    private int occupancy;
    private int messageCount;
    private int strongCount;
    private int weakCount;
    private long lastId;

    private Node head;
    private Node tail;
    private Node strongHead;
    private Node strongTail;
    private Node weakHead;
    private Node weakTail;

    private static final class Node {
        final long id;
        final boolean draft;
        final String validator;
        final byte[] content;

        Node next;
        Node validatorPrevious;
        Node validatorNext;

        Node(long id, boolean draft, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = (draft ? "W-" : "S-") + id;
            this.content = content;
        }
    }

    public static final class Message {
        private final long messageId;
        private final boolean draft;
        private final String validator;
        private final byte[] content;

        private Message(Node node) {
            this.messageId = node.id;
            this.draft = node.draft;
            this.validator = node.validator;
            this.content = copyBytes(node.content);
        }

        public long getMessageId() {
            return messageId;
        }

        public boolean isDraft() {
            return draft;
        }

        public String getValidator() {
            return validator;
        }

        public byte[] getContent() {
            return copyBytes(content);
        }
    }

    public static final class Header {
        private final int capacity;
        private final int occupancy;
        private final long[] messageIds;
        private final String[] strongValidators;
        private final String[] weakValidators;

        private Header(
                int capacity,
                int occupancy,
                long[] messageIds,
                String[] strongValidators,
                String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
            this.weakValidators = weakValidators;
        }

        public int getCapacity() {
            return capacity;
        }

        public int getOccupancy() {
            return occupancy;
        }

        public long[] getMessageIds() {
            long[] copy = new long[messageIds.length];
            for (int i = 0; i < messageIds.length; i++) {
                copy[i] = messageIds[i];
            }
            return copy;
        }

        public String[] getStrongValidators() {
            return copyStrings(strongValidators);
        }

        public String[] getWeakValidators() {
            return copyStrings(weakValidators);
        }
    }

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must not be null or empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("content exceeds capacity");
        }

        byte[] retainedContent = copyBytes(content);

        synchronized (lock) {
            for (Node node = head; node != null; node = node.next) {
                if (sameBytes(node.content, retainedContent)) {
                    return -1;
                }
            }

            if (lastId == MAX_ID) {
                throw new IllegalStateException("message IDs exhausted");
            }

            long id = lastId + 1;
            Node inserted = new Node(id, draft, retainedContent);

            while (occupancy > capacity - retainedContent.length) {
                removeOldest();
            }

            if (tail == null) {
                head = inserted;
            } else {
                tail.next = inserted;
            }
            tail = inserted;

            if (draft) {
                inserted.validatorPrevious = weakTail;
                if (weakTail == null) {
                    weakHead = inserted;
                } else {
                    weakTail.validatorNext = inserted;
                }
                weakTail = inserted;
                weakCount++;
            } else {
                inserted.validatorPrevious = strongTail;
                if (strongTail == null) {
                    strongHead = inserted;
                } else {
                    strongTail.validatorNext = inserted;
                }
                strongTail = inserted;
                strongCount++;
            }

            occupancy += retainedContent.length;
            messageCount++;
            lastId = id;
            return id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("messageId must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must not be null");
        }

        synchronized (lock) {
            for (Node node = head; node != null; node = node.next) {
                if (node.id == messageId) {
                    return node.validator.equals(validator) ? new Message(node) : null;
                }
            }
            return null;
        }
    }

    public int prune(long throughId) {
        if (throughId < 0) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }

        synchronized (lock) {
            int removed = 0;
            while (head != null && head.id <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[messageCount];
            String[] strong = new String[strongCount];
            String[] weak = new String[weakCount];

            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                ids[index++] = node.id;
            }

            index = 0;
            for (Node node = strongHead; node != null; node = node.validatorNext) {
                strong[index++] = node.validator;
            }

            index = 0;
            for (Node node = weakHead; node != null; node = node.validatorNext) {
                weak[index++] = node.validator;
            }

            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    private void removeOldest() {
        Node removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }

        Node previous = removed.validatorPrevious;
        Node next = removed.validatorNext;
        if (previous != null) {
            previous.validatorNext = next;
        } else if (removed.draft) {
            weakHead = next;
        } else {
            strongHead = next;
        }

        if (next != null) {
            next.validatorPrevious = previous;
        } else if (removed.draft) {
            weakTail = previous;
        } else {
            strongTail = previous;
        }

        if (removed.draft) {
            weakCount--;
        } else {
            strongCount--;
        }
        occupancy -= removed.content.length;
        messageCount--;
    }

    private static boolean sameBytes(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }
        return true;
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
}
