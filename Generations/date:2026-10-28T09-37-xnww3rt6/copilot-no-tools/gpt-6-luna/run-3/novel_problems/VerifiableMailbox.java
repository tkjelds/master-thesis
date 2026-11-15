@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object mutex = new Object();
    private final long capacity;

    private Entry head;
    private Entry tail;
    private int messageCount;
    private long occupancy;

    private ValidatorNode strongHead;
    private ValidatorNode strongTail;
    private int strongCount;

    private ValidatorNode weakHead;
    private ValidatorNode weakTail;
    private int weakCount;

    private long nextId = 1L;
    private boolean idsExhausted;

    public VerifiableMailbox(long capacity) {
        if (capacity <= 0L) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long receive(byte[] content, boolean draft) {
        if (content == null) {
            throw new IllegalArgumentException("Content must not be null");
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("Content must not be empty");
        }
        if ((long) content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds mailbox capacity");
        }

        byte[] retainedContent = copy(content);

        synchronized (mutex) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (sameBytes(entry.content, retainedContent)) {
                    return -1L;
                }
            }

            if (idsExhausted) {
                throw new IllegalStateException("Message ID space is exhausted");
            }

            while (occupancy + retainedContent.length > capacity) {
                removeOldest();
            }

            long id = nextId;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = id + 1L;
            }

            String validator = (draft ? "W-" : "S-") + id;
            Entry entry = new Entry(id, retainedContent, draft, validator);

            if (tail == null) {
                head = entry;
                tail = entry;
            } else {
                tail.next = entry;
                tail = entry;
            }

            messageCount++;
            occupancy += retainedContent.length;
            appendValidator(entry);
            return id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
        }

        synchronized (mutex) {
            for (Entry entry = head; entry != null; entry = entry.next) {
                if (entry.id == messageId && entry.validator.equals(validator)) {
                    return new Message(entry.id, entry.content, entry.draft, entry.validator);
                }
            }
            return null;
        }
    }

    public int prune(long throughId) {
        if (throughId < 0L) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }

        synchronized (mutex) {
            int removed = 0;
            while (head != null && head.id <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (mutex) {
            long[] messageIds = new long[messageCount];
            String[] strongValidators = new String[strongCount];
            String[] weakValidators = new String[weakCount];

            int index = 0;
            for (Entry entry = head; entry != null; entry = entry.next) {
                messageIds[index++] = entry.id;
            }

            index = 0;
            for (ValidatorNode node = strongHead; node != null; node = node.next) {
                strongValidators[index++] = node.entry.validator;
            }

            index = 0;
            for (ValidatorNode node = weakHead; node != null; node = node.next) {
                weakValidators[index++] = node.entry.validator;
            }

            return new Header(capacity, occupancy, messageIds, strongValidators, weakValidators);
        }
    }

    private void appendValidator(Entry entry) {
        ValidatorNode node = new ValidatorNode(entry);
        if (entry.draft) {
            if (weakTail == null) {
                weakHead = node;
                weakTail = node;
            } else {
                weakTail.next = node;
                weakTail = node;
            }
            weakCount++;
        } else {
            if (strongTail == null) {
                strongHead = node;
                strongTail = node;
            } else {
                strongTail.next = node;
                strongTail = node;
            }
            strongCount++;
        }
    }

    private void removeOldest() {
        Entry removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }
        removed.next = null;
        messageCount--;
        occupancy -= removed.content.length;

        if (removed.draft) {
            weakHead = weakHead.next;
            if (weakHead == null) {
                weakTail = null;
            }
            weakCount--;
        } else {
            strongHead = strongHead.next;
            if (strongHead == null) {
                strongTail = null;
            }
            strongCount--;
        }
    }

    private static boolean sameBytes(byte[] first, byte[] second) {
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

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] copy(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static long[] copy(long[] source) {
        long[] result = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    public static final class Message {
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;

        private Message(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = copy(content);
            this.draft = draft;
            this.validator = validator;
        }

        public long getId() {
            return id;
        }

        public long getMessageId() {
            return id;
        }

        public byte[] getContent() {
            return copy(content);
        }

        public boolean isDraft() {
            return draft;
        }

        public String getValidator() {
            return validator;
        }
    }

    public static final class Header {
        private final long capacity;
        private final long occupancy;
        private final long[] messageIds;
        private final String[] strongValidators;
        private final String[] weakValidators;

        private Header(long capacity, long occupancy, long[] messageIds,
                       String[] strongValidators, String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = copy(messageIds);
            this.strongValidators = copy(strongValidators);
            this.weakValidators = copy(weakValidators);
        }

        public long getCapacity() {
            return capacity;
        }

        public long getOccupancy() {
            return occupancy;
        }

        public long[] getMessageIds() {
            return copy(messageIds);
        }

        public String[] getStrongValidators() {
            return copy(strongValidators);
        }

        public String[] getWeakValidators() {
            return copy(weakValidators);
        }
    }

    private static final class Entry {
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;
        private Entry next;

        private Entry(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }
    }

    private static final class ValidatorNode {
        private final Entry entry;
        private ValidatorNode next;

        private ValidatorNode(Entry entry) {
            this.entry = entry;
        }
    }
}
