@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;

    private Node head;
    private Node tail;
    private int occupancy;
    private long nextId = 1L;
    private boolean idExhausted;

    private String[] strongValidators = new String[0];
    private int strongCount;
    private String[] weakValidators = new String[0];
    private int weakCount;

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

        byte[] copy = copyBytes(content);

        synchronized (lock) {
            for (Node node = head; node != null; node = node.next) {
                if (bytesEqual(node.content, copy)) {
                    return -1L;
                }
            }

            if (idExhausted) {
                throw new IllegalStateException("Message ID space is exhausted");
            }

            long id = nextId;
            String validator = (draft ? "W-" : "S-") + id;
            Node newNode = new Node(id, copy, draft, validator);

            if (draft) {
                ensureWeakCapacity(weakCount + 1);
            } else {
                ensureStrongCapacity(strongCount + 1);
            }

            while (occupancy > capacity - copy.length) {
                removeHead();
            }

            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            occupancy += copy.length;

            if (draft) {
                weakValidators[weakCount++] = validator;
            } else {
                strongValidators[strongCount++] = validator;
            }

            if (id == Long.MAX_VALUE) {
                idExhausted = true;
            } else {
                nextId = id + 1L;
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
            for (Node node = head; node != null; node = node.next) {
                if (node.id == messageId && node.validator.equals(validator)) {
                    return new Message(node.id, node.content, node.draft, node.validator);
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
                removeHead();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[countMessages()];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                ids[index++] = node.id;
            }

            String[] strong = new String[strongCount];
            for (int i = 0; i < strongCount; i++) {
                strong[i] = strongValidators[i];
            }

            String[] weak = new String[weakCount];
            for (int i = 0; i < weakCount; i++) {
                weak[i] = weakValidators[i];
            }

            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    private int countMessages() {
        int count = 0;
        for (Node node = head; node != null; node = node.next) {
            count++;
        }
        return count;
    }

    private void removeHead() {
        Node removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }

        occupancy -= removed.content.length;
        if (removed.draft) {
            removeValidator(weakValidators, weakCount, removed.validator);
            weakCount--;
        } else {
            removeValidator(strongValidators, strongCount, removed.validator);
            strongCount--;
        }
    }

    private static void removeValidator(String[] validators, int count, String validator) {
        int index = 0;
        while (index < count && !validators[index].equals(validator)) {
            index++;
        }
        if (index == count) {
            throw new IllegalStateException("Mailbox validator state is inconsistent");
        }
        for (int i = index; i < count - 1; i++) {
            validators[i] = validators[i + 1];
        }
        validators[count - 1] = null;
    }

    private void ensureStrongCapacity(int required) {
        if (required > strongValidators.length) {
            strongValidators = grow(strongValidators, required);
        }
    }

    private void ensureWeakCapacity(int required) {
        if (required > weakValidators.length) {
            weakValidators = grow(weakValidators, required);
        }
    }

    private static String[] grow(String[] current, int required) {
        int newLength = current.length == 0 ? 1 : current.length;
        while (newLength < required) {
            if (newLength > Integer.MAX_VALUE / 2) {
                newLength = Integer.MAX_VALUE;
            } else {
                newLength *= 2;
            }
        }

        String[] grown = new String[newLength];
        for (int i = 0; i < current.length; i++) {
            grown[i] = current[i];
        }
        return grown;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
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

    private static final class Node {
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;
        private Node next;

        private Node(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }
    }

    public static final class Message {
        public final long messageId;
        public final byte[] content;
        public final boolean draft;
        public final String validator;

        private Message(long messageId, byte[] content, boolean draft, String validator) {
            this.messageId = messageId;
            this.content = copyBytes(content);
            this.draft = draft;
            this.validator = validator;
        }

        public long getMessageId() {
            return messageId;
        }

        public byte[] getContent() {
            return copyBytes(content);
        }

        public boolean isDraft() {
            return draft;
        }

        public String getValidator() {
            return validator;
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

        private static String[] copyStrings(String[] source) {
            String[] copy = new String[source.length];
            for (int i = 0; i < source.length; i++) {
                copy[i] = source[i];
            }
            return copy;
        }
    }
}
