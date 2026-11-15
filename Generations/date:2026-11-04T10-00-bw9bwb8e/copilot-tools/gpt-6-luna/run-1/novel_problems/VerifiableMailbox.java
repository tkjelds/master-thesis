@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;

    private Node head;
    private Node tail;
    private int occupancy;

    private long nextId = 1L;
    private boolean idsExhausted;

    private final ValidatorSet strongValidators = new ValidatorSet();
    private final ValidatorSet weakValidators = new ValidatorSet();

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
                if (sameBytes(node.content, copy)) {
                    return -1L;
                }
            }

            if (idsExhausted) {
                throw new IllegalStateException("Message IDs are exhausted");
            }

            int requiredOccupancy = capacity - copy.length;
            while (occupancy > requiredOccupancy) {
                removeHead();
            }

            long id = nextId;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = id + 1L;
            }

            String validator = (draft ? "W-" : "S-") + id;
            Node inserted = new Node(id, draft, validator, copy);
            if (tail == null) {
                head = inserted;
                tail = inserted;
            } else {
                tail.next = inserted;
                tail = inserted;
            }
            occupancy += copy.length;

            if (draft) {
                weakValidators.add(validator);
            } else {
                strongValidators.add(validator);
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
                removeHead();
                removed++;
            }
            return removed;
        }
    }

    public SnapshotHeader snapshotHeader() {
        synchronized (lock) {
            long[] messageIds = new long[countMessages()];
            int index = 0;
            for (Node node = head; node != null; node = node.next) {
                messageIds[index++] = node.id;
            }
            return new SnapshotHeader(
                    capacity,
                    occupancy,
                    messageIds,
                    strongValidators.toArray(),
                    weakValidators.toArray());
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

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static final class Node {
        private final long id;
        private final boolean draft;
        private final String validator;
        private final byte[] content;
        private Node next;

        private Node(long id, boolean draft, String validator, byte[] content) {
            this.id = id;
            this.draft = draft;
            this.validator = validator;
            this.content = content;
        }
    }

    private static final class ValidatorSet {
        private ValidatorNode head;
        private ValidatorNode tail;
        private int size;

        private void add(String validator) {
            ValidatorNode node = new ValidatorNode(validator);
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            size++;
        }

        private void remove(String validator) {
            ValidatorNode previous = null;
            ValidatorNode current = head;
            while (current != null) {
                if (current.value.equals(validator)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    if (tail == current) {
                        tail = previous;
                    }
                    size--;
                    return;
                }
                previous = current;
                current = current.next;
            }
            throw new IllegalStateException("Validator set is inconsistent");
        }

        private String[] toArray() {
            String[] result = new String[size];
            int index = 0;
            for (ValidatorNode node = head; node != null; node = node.next) {
                result[index++] = node.value;
            }
            return result;
        }
    }

    private static final class ValidatorNode {
        private final String value;
        private ValidatorNode next;

        private ValidatorNode(String value) {
            this.value = value;
        }
    }

    public static final class Message {
        public final long id;
        public final boolean draft;
        public final String validator;
        private final byte[] content;

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

    public static final class SnapshotHeader {
        public final int capacity;
        public final int occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private SnapshotHeader(
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
            return copyLongs(messageIds);
        }

        public String[] getStrongValidators() {
            return copyStrings(strongValidators);
        }

        public String[] getWeakValidators() {
            return copyStrings(weakValidators);
        }

        private static long[] copyLongs(long[] source) {
            long[] copy = new long[source.length];
            for (int i = 0; i < source.length; i++) {
                copy[i] = source[i];
            }
            return copy;
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
