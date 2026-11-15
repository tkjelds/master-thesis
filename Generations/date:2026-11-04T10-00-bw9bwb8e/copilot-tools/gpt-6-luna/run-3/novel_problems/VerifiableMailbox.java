@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;
    private int occupancy;
    private Message head;
    private Message tail;
    private String[] strongValidators = new String[0];
    private String[] weakValidators = new String[0];
    private long nextMessageId = 1L;
    private boolean idsExhausted;

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
            throw new IllegalArgumentException("content exceeds mailbox capacity");
        }

        byte[] retainedContent = copy(content);
        synchronized (lock) {
            for (Message message = head; message != null; message = message.next) {
                if (sameBytes(message.content, retainedContent)) {
                    return -1L;
                }
            }

            if (idsExhausted) {
                throw new IllegalStateException("message ID space is exhausted");
            }

            long id = nextMessageId;
            String validator = (draft ? "W-" : "S-") + id;
            Message newMessage = new Message(id, retainedContent, draft, validator);

            int evictions = 0;
            long remainingOccupancy = occupancy;
            Message firstSurvivor = head;
            while (remainingOccupancy + retainedContent.length > capacity) {
                remainingOccupancy -= firstSurvivor.content.length;
                firstSurvivor = firstSurvivor.next;
                evictions++;
            }

            int strongCount = strongValidators.length + (draft ? 0 : 1);
            int weakCount = weakValidators.length + (draft ? 1 : 0);
            Message evicted = head;
            for (int i = 0; i < evictions; i++) {
                if (evicted.draft) {
                    weakCount--;
                } else {
                    strongCount--;
                }
                evicted = evicted.next;
            }

            String[] newStrongValidators = new String[strongCount];
            String[] newWeakValidators = new String[weakCount];
            int strongIndex = 0;
            int weakIndex = 0;
            for (Message message = firstSurvivor; message != null; message = message.next) {
                if (message.draft) {
                    newWeakValidators[weakIndex++] = message.validator;
                } else {
                    newStrongValidators[strongIndex++] = message.validator;
                }
            }
            if (draft) {
                newWeakValidators[weakIndex] = validator;
            } else {
                newStrongValidators[strongIndex] = validator;
            }

            for (int i = 0; i < evictions; i++) {
                head = head.next;
            }
            if (head == null) {
                tail = null;
            }
            if (tail == null) {
                tail = newMessage;
            } else {
                tail.next = newMessage;
            }
            if (head == null) {
                head = newMessage;
            }
            tail = newMessage;
            occupancy = (int) remainingOccupancy + retainedContent.length;
            strongValidators = newStrongValidators;
            weakValidators = newWeakValidators;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextMessageId = id + 1L;
            }
            return id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("messageId must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must not be null");
        }
        synchronized (lock) {
            for (Message message = head; message != null; message = message.next) {
                if (message.id == messageId && message.validator.equals(validator)) {
                    return new Message(message.id, copy(message.content), message.draft,
                            message.validator);
                }
            }
            return null;
        }
    }

    public int prune(long throughId) {
        if (throughId < 0L) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }
        synchronized (lock) {
            int removed = 0;
            int removedBytes = 0;
            Message survivor = head;
            while (survivor != null && survivor.id <= throughId) {
                removed++;
                removedBytes += survivor.content.length;
                survivor = survivor.next;
            }
            if (removed == 0) {
                return 0;
            }

            int strongCount = 0;
            int weakCount = 0;
            for (Message message = survivor; message != null; message = message.next) {
                if (message.draft) {
                    weakCount++;
                } else {
                    strongCount++;
                }
            }
            String[] newStrongValidators = new String[strongCount];
            String[] newWeakValidators = new String[weakCount];
            int strongIndex = 0;
            int weakIndex = 0;
            for (Message message = survivor; message != null; message = message.next) {
                if (message.draft) {
                    newWeakValidators[weakIndex++] = message.validator;
                } else {
                    newStrongValidators[strongIndex++] = message.validator;
                }
            }

            head = survivor;
            if (head == null) {
                tail = null;
            }
            occupancy -= removedBytes;
            strongValidators = newStrongValidators;
            weakValidators = newWeakValidators;
            return removed;
        }
    }

    public Snapshot snapshotHeader() {
        synchronized (lock) {
            int count = messageCount();
            long[] messageIds = new long[count];
            int index = 0;
            for (Message message = head; message != null; message = message.next) {
                messageIds[index++] = message.id;
            }
            return new Snapshot(capacity, occupancy, messageIds,
                    copy(strongValidators), copy(weakValidators));
        }
    }

    private int messageCount() {
        int count = 0;
        for (Message message = head; message != null; message = message.next) {
            count++;
        }
        return count;
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

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
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

    private static String[] copy(String[] source) {
        String[] result = new String[source.length];
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
        private Message next;

        private Message(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }

        public long getId() {
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

    public static final class Snapshot {
        private final int capacity;
        private final int occupancy;
        private final long[] messageIds;
        private final String[] strongValidators;
        private final String[] weakValidators;

        private Snapshot(int capacity, int occupancy, long[] messageIds,
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

        public int getOccupancy() {
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
}
