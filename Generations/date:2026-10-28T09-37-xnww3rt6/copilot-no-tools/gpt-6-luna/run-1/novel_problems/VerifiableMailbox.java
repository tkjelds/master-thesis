@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;
    private int occupancy;

    private Node head;
    private Node tail;

    private String[] strongValidators = new String[0];
    private String[] weakValidators = new String[0];

    private long nextId = 1L;
    private boolean idExhausted;

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

        byte[] retainedContent = copy(content);

        synchronized (lock) {
            for (Node node = head; node != null; node = node.next) {
                if (equalBytes(node.content, retainedContent)) {
                    return -1L;
                }
            }

            if (idExhausted) {
                throw new IllegalStateException("Message IDs are exhausted");
            }

            while (occupancy > capacity - retainedContent.length) {
                removeHead();
            }

            long id = nextId;
            if (id == Long.MAX_VALUE) {
                idExhausted = true;
            } else {
                nextId = id + 1L;
            }

            String validator = (draft ? "W-" : "S-") + id;
            Node node = new Node(id, retainedContent, draft, validator);

            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }

            occupancy += retainedContent.length;
            if (draft) {
                weakValidators = append(weakValidators, validator);
            } else {
                strongValidators = append(strongValidators, validator);
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
            int count = 0;
            for (Node node = head; node != null; node = node.next) {
                count++;
            }

            long[] messageIds = new long[count];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messageIds[index++] = node.id;
            }

            return new Header(
                capacity,
                occupancy,
                messageIds,
                strongValidators,
                weakValidators
            );
        }
    }

    private void removeHead() {
        Node removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }

        occupancy -= removed.content.length;
        if (removed.draft) {
            weakValidators = remove(weakValidators, removed.validator);
        } else {
            strongValidators = remove(strongValidators, removed.validator);
        }
    }

    private static String[] append(String[] values, String value) {
        String[] result = new String[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        result[values.length] = value;
        return result;
    }

    private static String[] remove(String[] values, String value) {
        int found = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            throw new IllegalStateException("Validator state is inconsistent");
        }

        String[] result = new String[values.length - 1];
        for (int source = 0, target = 0; source < values.length; source++) {
            if (source != found) {
                result[target++] = values[source];
            }
        }
        return result;
    }

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static boolean equalBytes(byte[] left, byte[] right) {
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
            this.content = copy(content);
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

    public static final class Header {
        public final int capacity;
        public final int occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private Header(
            int capacity,
            int occupancy,
            long[] messageIds,
            String[] strongValidators,
            String[] weakValidators
        ) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = copy(messageIds);
            this.strongValidators = copy(strongValidators);
            this.weakValidators = copy(weakValidators);
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
