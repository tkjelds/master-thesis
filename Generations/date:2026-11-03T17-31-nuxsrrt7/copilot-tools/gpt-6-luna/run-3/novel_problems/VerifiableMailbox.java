@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;
    private Message[] messages = new Message[0];
    private int messageCount;
    private int occupancy;
    private long lastId;

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

        byte[] copiedContent = content.clone();

        synchronized (lock) {
            for (int i = 0; i < messageCount; i++) {
                if (sameContent(messages[i].content, copiedContent)) {
                    return -1L;
                }
            }

            if (lastId == Long.MAX_VALUE) {
                throw new IllegalStateException("Message IDs are exhausted");
            }

            long id = lastId + 1L;
            String validator = (draft ? "W-" : "S-") + id;
            Message message = new Message(id, draft, validator, copiedContent);

            ensureMessageCapacity(messageCount + 1);
            if (draft) {
                ensureWeakValidatorCapacity(weakValidatorCount + 1);
            } else {
                ensureStrongValidatorCapacity(strongValidatorCount + 1);
            }

            while (occupancy > capacity - copiedContent.length) {
                removeMessageAt(0);
            }

            messages[messageCount++] = message;
            occupancy += copiedContent.length;
            if (draft) {
                weakValidators[weakValidatorCount++] = validator;
            } else {
                strongValidators[strongValidatorCount++] = validator;
            }
            lastId = id;
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
            for (int i = 0; i < messageCount; i++) {
                Message message = messages[i];
                if (message.id == messageId && message.validator.equals(validator)) {
                    return new Message(
                            message.id,
                            message.draft,
                            message.validator,
                            message.content.clone());
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
            while (removed < messageCount && messages[removed].id <= throughId) {
                removeMessageAt(removed);
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[messageCount];
            for (int i = 0; i < messageCount; i++) {
                ids[i] = messages[i].id;
            }

            String[] strong = new String[strongValidatorCount];
            for (int i = 0; i < strongValidatorCount; i++) {
                strong[i] = strongValidators[i];
            }

            String[] weak = new String[weakValidatorCount];
            for (int i = 0; i < weakValidatorCount; i++) {
                weak[i] = weakValidators[i];
            }

            return new Header(capacity, occupancy, ids, strong, weak);
        }
    }

    private void removeMessageAt(int index) {
        Message removed = messages[index];
        occupancy -= removed.content.length;

        if (removed.draft) {
            removeValidator(weakValidators, weakValidatorCount, removed.validator);
            weakValidatorCount--;
        } else {
            removeValidator(strongValidators, strongValidatorCount, removed.validator);
            strongValidatorCount--;
        }

        int elementsToMove = messageCount - index - 1;
        if (elementsToMove > 0) {
            System.arraycopy(messages, index + 1, messages, index, elementsToMove);
        }
        messages[--messageCount] = null;
    }

    private static void removeValidator(String[] validators, int count, String validator) {
        int index = 0;
        while (index < count && !validators[index].equals(validator)) {
            index++;
        }
        if (index == count) {
            throw new IllegalStateException("Validator state is inconsistent");
        }

        int elementsToMove = count - index - 1;
        if (elementsToMove > 0) {
            System.arraycopy(validators, index + 1, validators, index, elementsToMove);
        }
        validators[count - 1] = null;
    }

    private void ensureMessageCapacity(int required) {
        if (messages.length >= required) {
            return;
        }
        int newCapacity = expandedCapacity(messages.length, required);
        Message[] expanded = new Message[newCapacity];
        System.arraycopy(messages, 0, expanded, 0, messageCount);
        messages = expanded;
    }

    private void ensureStrongValidatorCapacity(int required) {
        if (strongValidators.length >= required) {
            return;
        }
        int newCapacity = expandedCapacity(strongValidators.length, required);
        String[] expanded = new String[newCapacity];
        System.arraycopy(strongValidators, 0, expanded, 0, strongValidatorCount);
        strongValidators = expanded;
    }

    private void ensureWeakValidatorCapacity(int required) {
        if (weakValidators.length >= required) {
            return;
        }
        int newCapacity = expandedCapacity(weakValidators.length, required);
        String[] expanded = new String[newCapacity];
        System.arraycopy(weakValidators, 0, expanded, 0, weakValidatorCount);
        weakValidators = expanded;
    }

    private static int expandedCapacity(int current, int required) {
        int expanded = current == 0 ? 1 : current;
        while (expanded < required) {
            if (expanded > Integer.MAX_VALUE / 2) {
                expanded = required;
            } else {
                expanded *= 2;
            }
        }
        return expanded;
    }

    private static boolean sameContent(byte[] first, byte[] second) {
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

    public static final class Message {
        private final long id;
        private final boolean draft;
        private final String validator;
        private final byte[] content;

        private Message(long id, boolean draft, String validator, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = validator;
            this.content = content;
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
            return content.clone();
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
