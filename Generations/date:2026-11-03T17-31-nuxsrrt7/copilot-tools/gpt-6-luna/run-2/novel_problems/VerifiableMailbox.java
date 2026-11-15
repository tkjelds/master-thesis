@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;

    private Node head;
    private Node tail;
    private int occupancy;
    private long lastAssignedId;

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

        byte[] copy = content.clone();

        synchronized (lock) {
            for (Node node = head; node != null; node = node.next) {
                if (sameContent(node.message.content, copy)) {
                    return -1L;
                }
            }

            if (lastAssignedId == Long.MAX_VALUE) {
                throw new IllegalStateException("Message ID space exhausted");
            }

            int bytesToFree = occupancy;
            int evictedStrong = 0;
            int evictedWeak = 0;
            Node firstToEvict = head;
            while (bytesToFree > capacity - copy.length) {
                if (firstToEvict.message.draft) {
                    evictedWeak++;
                } else {
                    evictedStrong++;
                }
                bytesToFree -= firstToEvict.message.content.length;
                firstToEvict = firstToEvict.next;
            }

            long id = lastAssignedId + 1L;
            String validator = (draft ? "W-" : "S-") + id;
            Message message = new Message(id, draft, validator, copy);
            Node newNode = new Node(message);

            String[] updatedValidators = null;
            if (draft) {
                updatedValidators = expandedArray(
                        weakValidators, weakCount - evictedWeak + 1);
            } else {
                updatedValidators = expandedArray(
                        strongValidators, strongCount - evictedStrong + 1);
            }

            if (draft) {
                weakValidators = updatedValidators;
            } else {
                strongValidators = updatedValidators;
            }

            while (occupancy > capacity - copy.length) {
                removeOldestLocked();
            }

            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                newNode.previous = tail;
                tail = newNode;
            }
            occupancy += copy.length;
            lastAssignedId = id;

            if (draft) {
                weakValidators[weakCount++] = validator;
            } else {
                strongValidators[strongCount++] = validator;
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
                Message message = node.message;
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
            while (head != null && head.message.id <= throughId) {
                removeOldestLocked();
                removed++;
            }
            return removed;
        }
    }

    public Header snapshotHeader() {
        synchronized (lock) {
            long[] ids = new long[countMessagesLocked()];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                ids[index++] = node.message.id;
            }

            String[] strongCopy = new String[strongCount];
            System.arraycopy(strongValidators, 0, strongCopy, 0, strongCount);
            String[] weakCopy = new String[weakCount];
            System.arraycopy(weakValidators, 0, weakCopy, 0, weakCount);

            return new Header(capacity, occupancy, ids, strongCopy, weakCopy);
        }
    }

    private int countMessagesLocked() {
        int count = 0;
        for (Node node = head; node != null; node = node.next) {
            count++;
        }
        return count;
    }

    private void removeOldestLocked() {
        Node removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        } else {
            head.previous = null;
        }

        occupancy -= removed.message.content.length;
        if (removed.message.draft) {
            removeValidator(weakValidators, true, removed.message.validator);
        } else {
            removeValidator(strongValidators, false, removed.message.validator);
        }
    }

    private void removeValidator(String[] validators, boolean weak, String validator) {
        int count = weak ? weakCount : strongCount;
        int index = 0;
        while (index < count && !validators[index].equals(validator)) {
            index++;
        }

        if (index == count) {
            throw new IllegalStateException("Mailbox validator invariant violated");
        }

        int elementsAfter = count - index - 1;
        if (elementsAfter > 0) {
            System.arraycopy(validators, index + 1, validators, index, elementsAfter);
        }
        validators[count - 1] = null;

        if (weak) {
            weakCount--;
        } else {
            strongCount--;
        }
    }

    private static String[] expandedArray(String[] current, int required) {
        if (required <= current.length) {
            return current;
        }

        int newLength = current.length == 0 ? 4 : current.length;
        while (newLength < required) {
            if (newLength > Integer.MAX_VALUE / 2) {
                newLength = required;
                break;
            }
            newLength *= 2;
        }

        String[] expanded = new String[newLength];
        System.arraycopy(current, 0, expanded, 0, current.length);
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
        private final Message message;
        private Node previous;
        private Node next;

        private Node(Message message) {
            this.message = message;
        }
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
