@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;

    private MessageNode head;
    private MessageNode tail;
    private int occupancy;

    private long nextId = 1L;
    private boolean idsExhausted;

    private String[] strongValidators = new String[4];
    private int strongValidatorCount;
    private String[] weakValidators = new String[4];
    private int weakValidatorCount;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Content must not be null or empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds mailbox capacity");
        }

        byte[] retainedContent = copyBytes(content);

        synchronized (lock) {
            for (MessageNode node = head; node != null; node = node.next) {
                if (bytesEqual(node.content, retainedContent)) {
                    return -1L;
                }
            }

            if (idsExhausted) {
                throw new IllegalStateException("Message ID space exhausted");
            }

            while (occupancy > capacity - retainedContent.length) {
                removeOldest();
            }

            long id = nextId;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = id + 1L;
            }

            String validator = (draft ? "W-" : "S-") + id;
            MessageNode node = new MessageNode(id, draft, validator, retainedContent);

            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            occupancy += retainedContent.length;

            if (draft) {
                addValidator(false, validator);
            } else {
                addValidator(true, validator);
            }

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

        synchronized (lock) {
            for (MessageNode node = head; node != null; node = node.next) {
                if (node.id == messageId && node.validator.equals(validator)) {
                    return new Message(node.id, node.draft, node.validator, node.content);
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
            while (head != null && head.id <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            int messageCount = 0;
            for (MessageNode node = head; node != null; node = node.next) {
                messageCount++;
            }

            long[] ids = new long[messageCount];
            int index = 0;
            for (MessageNode node = head; node != null; node = node.next) {
                ids[index++] = node.id;
            }

            String[] strong = copyStrings(strongValidators, strongValidatorCount);
            String[] weak = copyStrings(weakValidators, weakValidatorCount);
            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    private void removeOldest() {
        MessageNode removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }

        occupancy -= removed.content.length;
        if (removed.draft) {
            removeValidator(false, removed.validator);
        } else {
            removeValidator(true, removed.validator);
        }
    }

    private void addValidator(boolean strong, String validator) {
        if (strong) {
            if (strongValidatorCount == strongValidators.length) {
                strongValidators = growStrings(strongValidators, strongValidatorCount);
            }
            strongValidators[strongValidatorCount++] = validator;
        } else {
            if (weakValidatorCount == weakValidators.length) {
                weakValidators = growStrings(weakValidators, weakValidatorCount);
            }
            weakValidators[weakValidatorCount++] = validator;
        }
    }

    private void removeValidator(boolean strong, String validator) {
        String[] values = strong ? strongValidators : weakValidators;
        int count = strong ? strongValidatorCount : weakValidatorCount;

        for (int i = 0; i < count; i++) {
            if (values[i].equals(validator)) {
                int elementsToMove = count - i - 1;
                if (elementsToMove > 0) {
                    System.arraycopy(values, i + 1, values, i, elementsToMove);
                }
                values[--count] = null;

                if (strong) {
                    strongValidatorCount = count;
                } else {
                    weakValidatorCount = count;
                }
                return;
            }
        }
    }

    private static String[] growStrings(String[] values, int count) {
        int newLength;
        if (values.length == 0) {
            newLength = 4;
        } else if (values.length <= Integer.MAX_VALUE / 2) {
            newLength = values.length * 2;
        } else {
            newLength = Integer.MAX_VALUE;
        }
        if (newLength <= count) {
            throw new OutOfMemoryError("Validator set is too large");
        }

        String[] grown = new String[newLength];
        System.arraycopy(values, 0, grown, 0, count);
        return grown;
    }

    private static String[] copyStrings(String[] values, int count) {
        String[] copy = new String[count];
        System.arraycopy(values, 0, copy, 0, count);
        return copy;
    }

    private static byte[] copyBytes(byte[] bytes) {
        byte[] copy = new byte[bytes.length];
        System.arraycopy(bytes, 0, copy, 0, bytes.length);
        return copy;
    }

    private static boolean bytesEqual(byte[] left, byte[] right) {
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

    private static final class MessageNode {
        final long id;
        final boolean draft;
        final String validator;
        final byte[] content;
        MessageNode next;

        MessageNode(long id, boolean draft, String validator, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = validator;
            this.content = content;
        }
    }

    public static final class Message {
        public final long id;
        public final boolean draft;
        public final String validator;
        public final byte[] content;

        private Message(long id, boolean draft, String validator, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = validator;
            this.content = copyBytes(content);
        }

        public long getId() {
            return id;
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
        public final int capacity;
        public final int occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private Header(int capacity, int occupancy, long[] messageIds,
                       String[] strongValidators, String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = copyLongs(messageIds);
            this.strongValidators = copyStrings(strongValidators, strongValidators.length);
            this.weakValidators = copyStrings(weakValidators, weakValidators.length);
        }

        public int getCapacity() {
            return capacity;
        }

        public int getOccupancy() {
            return occupancy;
        }

        public long[] getMessageIds() {
            return copyLongs(messageIds);
        }

        public String[] getStrongValidators() {
            return copyStrings(strongValidators, strongValidators.length);
        }

        public String[] getWeakValidators() {
            return copyStrings(weakValidators, weakValidators.length);
        }
    }

    private static long[] copyLongs(long[] values) {
        long[] copy = new long[values.length];
        System.arraycopy(values, 0, copy, 0, values.length);
        return copy;
    }
}
