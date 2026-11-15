@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;
    private int occupancy;
    private int messageCount;
    private Node head;
    private Node tail;
    private long nextId = 1L;
    private boolean idsExhausted;

    private String[] strongValidators = new String[0];
    private int strongValidatorCount;
    private String[] weakValidators = new String[0];
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
        byte[] retainedContent = content.clone();

        synchronized (lock) {
            for (Node node = head; node != null; node = node.next) {
                if (sameContent(node.content, retainedContent)) {
                    return -1L;
                }
            }

            if (idsExhausted) {
                throw new IllegalStateException("Message IDs are exhausted");
            }

            int bytesToFree = Math.max(0, occupancy - (capacity - retainedContent.length));
            int bytesFreed = 0;
            int messagesToEvict = 0;
            int strongToRemove = 0;
            int weakToRemove = 0;

            for (Node node = head; bytesFreed < bytesToFree; node = node.next) {
                bytesFreed += node.content.length;
                messagesToEvict++;
                if (node.draft) {
                    weakToRemove++;
                } else {
                    strongToRemove++;
                }
            }

            int resultingValidatorCount;
            if (draft) {
                resultingValidatorCount = weakValidatorCount - weakToRemove + 1;
                ensureWeakValidatorCapacity(resultingValidatorCount);
            } else {
                resultingValidatorCount = strongValidatorCount - strongToRemove + 1;
                ensureStrongValidatorCapacity(resultingValidatorCount);
            }

            long id = nextId;
            String validator = (draft ? "W-" : "S-") + id;
            Node newNode = new Node(id, retainedContent, draft, validator);

            for (int i = 0; i < messagesToEvict; i++) {
                removeHead();
            }

            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            occupancy += retainedContent.length;
            messageCount++;

            if (draft) {
                weakValidators[weakValidatorCount++] = validator;
            } else {
                strongValidators[strongValidatorCount++] = validator;
            }

            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
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

    public HeaderSnapshot snapshotHeader() {
        synchronized (lock) {
            long[] messageIds = new long[messageCount];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messageIds[index++] = node.id;
            }

            String[] strongCopy = new String[strongValidatorCount];
            for (int i = 0; i < strongValidatorCount; i++) {
                strongCopy[i] = strongValidators[i];
            }

            String[] weakCopy = new String[weakValidatorCount];
            for (int i = 0; i < weakValidatorCount; i++) {
                weakCopy[i] = weakValidators[i];
            }

            return new HeaderSnapshot(capacity, occupancy, messageIds, strongCopy, weakCopy);
        }
    }

    private void removeHead() {
        Node removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }
        occupancy -= removed.content.length;
        messageCount--;

        if (removed.draft) {
            removeValidator(weakValidators, weakValidatorCount, removed.validator);
            weakValidatorCount--;
        } else {
            removeValidator(strongValidators, strongValidatorCount, removed.validator);
            strongValidatorCount--;
        }
    }

    private static void removeValidator(String[] validators, int count, String validator) {
        int index = 0;
        while (index < count && !validators[index].equals(validator)) {
            index++;
        }
        if (index == count) {
            throw new IllegalStateException("Validator state is inconsistent");
        }
        for (int i = index; i < count - 1; i++) {
            validators[i] = validators[i + 1];
        }
        validators[count - 1] = null;
    }

    private void ensureStrongValidatorCapacity(int needed) {
        if (needed <= strongValidators.length) {
            return;
        }
        int newLength = expandedLength(strongValidators.length, needed);
        String[] expanded = new String[newLength];
        for (int i = 0; i < strongValidatorCount; i++) {
            expanded[i] = strongValidators[i];
        }
        strongValidators = expanded;
    }

    private void ensureWeakValidatorCapacity(int needed) {
        if (needed <= weakValidators.length) {
            return;
        }
        int newLength = expandedLength(weakValidators.length, needed);
        String[] expanded = new String[newLength];
        for (int i = 0; i < weakValidatorCount; i++) {
            expanded[i] = weakValidators[i];
        }
        weakValidators = expanded;
    }

    private static int expandedLength(int current, int needed) {
        int expanded = current == 0 ? 4 : current;
        while (expanded < needed) {
            if (expanded > Integer.MAX_VALUE / 2) {
                return needed;
            }
            expanded *= 2;
        }
        return expanded;
    }

    private static boolean sameContent(byte[] left, byte[] right) {
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
        public final long id;
        public final byte[] content;
        public final boolean draft;
        public final String validator;

        private Message(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content.clone();
            this.draft = draft;
            this.validator = validator;
        }

        public long getId() {
            return id;
        }

        public byte[] getContent() {
            return content.clone();
        }

        public boolean isDraft() {
            return draft;
        }

        public String getValidator() {
            return validator;
        }
    }

    public static final class HeaderSnapshot {
        public final int capacity;
        public final int occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private HeaderSnapshot(int capacity, int occupancy, long[] messageIds,
                               String[] strongValidators, String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds.clone();
            this.strongValidators = strongValidators.clone();
            this.weakValidators = weakValidators.clone();
        }

        public int getCapacity() {
            return capacity;
        }

        public int getOccupancy() {
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
}
