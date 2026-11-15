@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final int capacity;

    private MessageNode head;
    private MessageNode tail;
    private int occupancy;
    private int messageCount;

    private ValidatorNode strongHead;
    private ValidatorNode strongTail;
    private int strongCount;

    private ValidatorNode weakHead;
    private ValidatorNode weakTail;
    private int weakCount;

    private long nextId = 1L;
    private boolean idSpaceExhausted;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Stores a copy of the content. When necessary, oldest messages are evicted
     * immediately to make room; this operation never waits for capacity.
     *
     * @return the new message ID, or -1 if identical content is already stored
     */
    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Content must not be null or empty");
        }
        if (content.length > capacity) {
            throw new IllegalArgumentException("Content exceeds mailbox capacity");
        }

        synchronized (this) {
            byte[] copy = new byte[content.length];
            for (int i = 0; i < content.length; i++) {
                copy[i] = content[i];
            }

            for (MessageNode current = head; current != null; current = current.next) {
                if (sameBytes(current.content, copy)) {
                    return -1L;
                }
            }

            if (idSpaceExhausted) {
                throw new IllegalStateException("Message ID space is exhausted");
            }

            long id = nextId;
            String validator = (draft ? "W-" : "S-") + id;
            MessageNode newMessage = new MessageNode(id, copy, draft, validator);
            ValidatorNode newValidator = new ValidatorNode(validator);

            while (occupancy > capacity - copy.length) {
                removeOldest();
            }

            if (tail == null) {
                head = newMessage;
                tail = newMessage;
            } else {
                tail.next = newMessage;
                tail = newMessage;
            }
            occupancy += copy.length;
            messageCount++;

            if (draft) {
                appendWeakValidator(newValidator);
            } else {
                appendStrongValidator(newValidator);
            }

            if (id == Long.MAX_VALUE) {
                idSpaceExhausted = true;
            } else {
                nextId = id + 1L;
            }
            return id;
        }
    }

    /**
     * Returns a detached copy if both the ID and validator match a stored
     * message. Fetching does not remove the message.
     */
    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
        }

        synchronized (this) {
            for (MessageNode current = head; current != null; current = current.next) {
                if (current.id == messageId && current.validator.equals(validator)) {
                    return new Message(current.id, current.content, current.draft, current.validator);
                }
            }
            return null;
        }
    }

    /**
     * Removes all messages whose IDs are at most throughId.
     *
     * @return the number of messages removed
     */
    public int prune(long throughId) {
        if (throughId < 0L) {
            throw new IllegalArgumentException("throughId must be nonnegative");
        }

        synchronized (this) {
            int removed = 0;
            while (head != null && head.id <= throughId) {
                removeOldest();
                removed++;
            }
            return removed;
        }
    }

    /**
     * Returns a detached snapshot of the mailbox header and validator sets.
     */
    public Header snapshotHeader() {
        synchronized (this) {
            long[] ids = new long[messageCount];
            int index = 0;
            for (MessageNode current = head; current != null; current = current.next) {
                ids[index++] = current.id;
            }

            String[] strongValidators = new String[strongCount];
            index = 0;
            for (ValidatorNode current = strongHead; current != null; current = current.next) {
                strongValidators[index++] = current.value;
            }

            String[] weakValidators = new String[weakCount];
            index = 0;
            for (ValidatorNode current = weakHead; current != null; current = current.next) {
                weakValidators[index++] = current.value;
            }

            return new Header(capacity, occupancy, ids, strongValidators, weakValidators);
        }
    }

    private void removeOldest() {
        MessageNode removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }

        occupancy -= removed.content.length;
        messageCount--;

        if (removed.draft) {
            removeWeakValidator(removed.validator);
        } else {
            removeStrongValidator(removed.validator);
        }
    }

    private void appendStrongValidator(ValidatorNode node) {
        if (strongTail == null) {
            strongHead = node;
            strongTail = node;
        } else {
            strongTail.next = node;
            strongTail = node;
        }
        strongCount++;
    }

    private void appendWeakValidator(ValidatorNode node) {
        if (weakTail == null) {
            weakHead = node;
            weakTail = node;
        } else {
            weakTail.next = node;
            weakTail = node;
        }
        weakCount++;
    }

    private void removeStrongValidator(String value) {
        ValidatorNode previous = null;
        ValidatorNode current = strongHead;
        while (current != null) {
            if (current.value.equals(value)) {
                if (previous == null) {
                    strongHead = current.next;
                } else {
                    previous.next = current.next;
                }
                if (strongTail == current) {
                    strongTail = previous;
                }
                strongCount--;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private void removeWeakValidator(String value) {
        ValidatorNode previous = null;
        ValidatorNode current = weakHead;
        while (current != null) {
            if (current.value.equals(value)) {
                if (previous == null) {
                    weakHead = current.next;
                } else {
                    previous.next = current.next;
                }
                if (weakTail == current) {
                    weakTail = previous;
                }
                weakCount--;
                return;
            }
            previous = current;
            current = current.next;
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

    private static final class MessageNode {
        final long id;
        final byte[] content;
        final boolean draft;
        final String validator;
        MessageNode next;

        MessageNode(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }
    }

    private static final class ValidatorNode {
        final String value;
        ValidatorNode next;

        ValidatorNode(String value) {
            this.value = value;
        }
    }

    public static final class Message {
        public final long id;
        public final byte[] content;
        public final boolean draft;
        public final String validator;

        private Message(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = new byte[content.length];
            for (int i = 0; i < content.length; i++) {
                this.content[i] = content[i];
            }
            this.draft = draft;
            this.validator = validator;
        }

        public byte[] getContent() {
            byte[] copy = new byte[content.length];
            for (int i = 0; i < content.length; i++) {
                copy[i] = content[i];
            }
            return copy;
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
    }
}
