@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long capacity;

    private MessageNode head;
    private MessageNode tail;
    private long occupancy;
    private long nextId = 1L;
    private boolean idsExhausted;
    private final ValidatorSet weakValidators = new ValidatorSet();
    private final ValidatorSet strongValidators = new ValidatorSet();

    public VerifiableMailbox(long capacity) {
        if (capacity <= 0L) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must not be null or empty");
        }
        if ((long) content.length > capacity) {
            throw new IllegalArgumentException("content exceeds mailbox capacity");
        }

        byte[] retainedContent = copy(content);
        synchronized (lock) {
            for (MessageNode node = head; node != null; node = node.next) {
                if (sameBytes(node.content, retainedContent)) {
                    return -1L;
                }
            }

            if (idsExhausted) {
                throw new IllegalStateException("message IDs are exhausted");
            }

            long id = nextId;
            String validator = (draft ? "W-" : "S-") + id;
            MessageNode added = new MessageNode(id, retainedContent, draft, validator);
            ValidatorSet targetValidators = draft ? weakValidators : strongValidators;
            targetValidators.ensureCapacity();

            while (retainedContent.length > capacity - occupancy) {
                removeHead();
            }

            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = id + 1L;
            }

            if (tail == null) {
                head = added;
                tail = added;
            } else {
                tail.next = added;
                tail = added;
            }
            occupancy += retainedContent.length;
            targetValidators.add(validator);
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
            for (MessageNode node = head; node != null; node = node.next) {
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

    public Snapshot snapshotHeader() {
        synchronized (lock) {
            int count = 0;
            for (MessageNode node = head; node != null; node = node.next) {
                count++;
            }

            long[] messageIds = new long[count];
            int index = 0;
            for (MessageNode node = head; node != null; node = node.next) {
                messageIds[index++] = node.id;
            }
            return new Snapshot(
                    capacity,
                    occupancy,
                    messageIds,
                    strongValidators.copy(),
                    weakValidators.copy());
        }
    }

    private void removeHead() {
        MessageNode removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }
        occupancy -= removed.content.length;
        if (removed.draft) {
            weakValidators.remove(removed.validator);
        } else {
            strongValidators.remove(removed.validator);
        }
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

    private static final class MessageNode {
        private final long id;
        private final byte[] content;
        private final boolean draft;
        private final String validator;
        private MessageNode next;

        private MessageNode(long id, byte[] content, boolean draft, String validator) {
            this.id = id;
            this.content = content;
            this.draft = draft;
            this.validator = validator;
        }
    }

    private static final class ValidatorSet {
        private String[] values = new String[0];
        private int size;

        private void ensureCapacity() {
            if (size == values.length) {
                int newLength;
                if (values.length == Integer.MAX_VALUE) {
                    throw new OutOfMemoryError("validator set is too large");
                }
                long candidate = (long) values.length + values.length / 2L + 1L;
                newLength = candidate > Integer.MAX_VALUE
                        ? Integer.MAX_VALUE
                        : (int) candidate;
                String[] expanded = new String[newLength];
                for (int i = 0; i < size; i++) {
                    expanded[i] = values[i];
                }
                values = expanded;
            }
        }

        private void add(String validator) {
            if (size == values.length) {
                throw new IllegalStateException("validator set capacity was not reserved");
            }
            values[size++] = validator;
        }

        private void remove(String validator) {
            int index = 0;
            while (index < size && !values[index].equals(validator)) {
                index++;
            }
            if (index == size) {
                throw new IllegalStateException("validator set is inconsistent");
            }
            for (int i = index; i < size - 1; i++) {
                values[i] = values[i + 1];
            }
            values[--size] = null;
        }

        private String[] copy() {
            String[] result = new String[size];
            for (int i = 0; i < size; i++) {
                result[i] = values[i];
            }
            return result;
        }
    }

    public static final class Message {
        private final long messageId;
        private final byte[] content;
        private final boolean draft;
        private final String validator;

        private Message(long messageId, byte[] content, boolean draft, String validator) {
            this.messageId = messageId;
            this.content = copy(content);
            this.draft = draft;
            this.validator = validator;
        }

        public long getMessageId() {
            return messageId;
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
        private final long capacity;
        private final long occupancy;
        private final long[] messageIds;
        private final String[] strongValidators;
        private final String[] weakValidators;

        private Snapshot(
                long capacity,
                long occupancy,
                long[] messageIds,
                String[] strongValidators,
                String[] weakValidators) {
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
    }
}
