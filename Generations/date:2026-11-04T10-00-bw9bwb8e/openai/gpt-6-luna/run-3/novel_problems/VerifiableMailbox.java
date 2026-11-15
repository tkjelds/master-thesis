@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final long capacity;
    private final Object lock = new Object();

    private Node head;
    private Node tail;
    private long occupancy;
    private long lastAssignedId;
    private int messageCount;

    private final ValidatorSet weakValidators = new ValidatorSet();
    private final ValidatorSet strongValidators = new ValidatorSet();

    public VerifiableMailbox(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Adds a message, evicting oldest messages as needed. Returns -1 if the
     * content is already present, regardless of the draft status.
     */
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Content must not be null or empty");
        }
        if ((long) content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds mailbox capacity");
        }

        byte[] retainedContent = copyBytes(content);

        synchronized (lock) {
            for (Node node = head; node != null; node = node.next) {
                if (bytesEqual(node.content, retainedContent)) {
                    return -1L;
                }
            }

            if (lastAssignedId == Long.MAX_VALUE) {
                throw new IllegalStateException("Message ID space exhausted");
            }

            while (occupancy > capacity - retainedContent.length) {
                removeOldest();
            }

            long id = lastAssignedId + 1L;
            String validator = (draft ? "W-" : "S-") + id;
            Node node = new Node(id, validator, draft, retainedContent);

            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }

            occupancy += retainedContent.length;
            messageCount++;
            lastAssignedId = id;

            if (draft) {
                weakValidators.add(validator);
            } else {
                strongValidators.add(validator);
            }

            return id;
        }
    }

    /**
     * Returns a detached message if both its ID and validator match; otherwise
     * returns null. Fetching does not remove the message.
     */
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
        }

        synchronized (lock) {
            for (Node node = head; node != null; node = node.next) {
                if (node.id == messageId && node.validator.equals(validator)) {
                    return new Message(node.id, node.validator, node.draft, node.content);
                }
            }
            return null;
        }
    }

    /**
     * Removes all messages whose IDs are at most throughId and returns the
     * number removed.
     */
    public long prune(long throughId) {
        if (throughId < 0L) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }

        synchronized (lock) {
            long removed = 0L;
            while (head != null && head.id <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    /**
     * Returns a coherent snapshot of the mailbox header.
     */
    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[messageCount];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                ids[index++] = node.id;
            }

            String[] strong = strongValidators.toArray();
            String[] weak = weakValidators.toArray();
            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    private void removeOldest() {
        Node removed = head;
        if (removed == null) {
            return;
        }

        head = removed.next;
        if (head == null) {
            tail = null;
        }

        occupancy -= removed.content.length;
        messageCount--;

        if (removed.draft) {
            weakValidators.remove(removed.validator);
        } else {
            strongValidators.remove(removed.validator);
        }
    }

    private static boolean bytesEqual(byte[] first, byte[] second) {
        if (first.length != second.length) {
            return false;
        }
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) {
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

    private static final class Node {
        private final long id;
        private final String validator;
        private final boolean draft;
        private final byte[] content;
        private Node next;

        private Node(long id, String validator, boolean draft, byte[] content) {
            this.id = id;
            this.validator = validator;
            this.draft = draft;
            this.content = content;
        }
    }

    private static final class ValidatorSet {
        private ValidatorEntry head;
        private int size;

        private void add(String validator) {
            head = new ValidatorEntry(validator, head);
            size++;
        }

        private void remove(String validator) {
            ValidatorEntry previous = null;
            ValidatorEntry current = head;

            while (current != null) {
                if (current.value.equals(validator)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    size--;
                    return;
                }
                previous = current;
                current = current.next;
            }
        }

        private String[] toArray() {
            String[] result = new String[size];
            int index = 0;
            for (ValidatorEntry entry = head; entry != null; entry = entry.next) {
                result[index++] = entry.value;
            }
            return result;
        }
    }

    private static final class ValidatorEntry {
        private final String value;
        private ValidatorEntry next;

        private ValidatorEntry(String value, ValidatorEntry next) {
            this.value = value;
            this.next = next;
        }
    }

    public static final class Message {
        public final long id;
        public final String validator;
        public final boolean draft;
        private final byte[] content;

        private Message(long id, String validator, boolean draft, byte[] content) {
            this.id = id;
            this.validator = validator;
            this.draft = draft;
            this.content = copyBytes(content);
        }

        public long getId() {
            return id;
        }

        public String getValidator() {
            return validator;
        }

        public boolean isDraft() {
            return draft;
        }

        public byte[] getContent() {
            return copyBytes(content);
        }
    }

    public static final class Header {
        public final long capacity;
        public final long occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private Header(
                long capacity,
                long occupancy,
                long[] messageIds,
                String[] strongValidators,
                String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = copyLongs(messageIds);
            this.strongValidators = copyStrings(strongValidators);
            this.weakValidators = copyStrings(weakValidators);
        }

        public long getCapacity() {
            return capacity;
        }

        public long getOccupancy() {
            return occupancy;
        }

        public long[] getMessageIds() {
            return copyLongs(messageIds);
        }

        public String[] getStrongValidators() {
            return copyStrings(strongValidators);
        }

        public String[] getWeakValidators() {
            return copyStrings(weakValidators);
        }
    }

    private static long[] copyLongs(long[] source) {
        long[] copy = new long[source.length];
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
