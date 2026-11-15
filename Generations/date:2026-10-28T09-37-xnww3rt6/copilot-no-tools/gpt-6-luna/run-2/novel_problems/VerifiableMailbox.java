@VerifiableMailbox.ThreadSafe
public final class VerifiableMailbox {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long capacity;
    private long occupancy;
    private long nextMessageId = 1;
    private boolean idExhausted;

    private MessageNode head;
    private MessageNode tail;
    private ValidatorNode strongHead;
    private ValidatorNode strongTail;
    private ValidatorNode weakHead;
    private ValidatorNode weakTail;

    public VerifiableMailbox(long capacity) {
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

        byte[] payload = content.clone();

        synchronized (lock) {
            for (MessageNode node = head; node != null; node = node.next) {
                if (sameBytes(node.content, payload)) {
                    return -1;
                }
            }

            if (idExhausted) {
                throw new IllegalStateException("Message ID space exhausted");
            }

            while (occupancy + payload.length > capacity) {
                removeHead();
            }

            long id = nextMessageId;
            if (id == Long.MAX_VALUE) {
                idExhausted = true;
            } else {
                nextMessageId++;
            }

            String validator = (draft ? "W-" : "S-") + id;
            MessageNode node = new MessageNode(id, payload, draft, validator);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            occupancy += payload.length;

            if (draft) {
                appendValidator(true, validator);
            } else {
                appendValidator(false, validator);
            }

            return id;
        }
    }

    public Message fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        if (validator == null) {
            throw new IllegalArgumentException("Validator must not be null");
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
        if (throughId < 0) {
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
            int messageCount = 0;
            for (MessageNode node = head; node != null; node = node.next) {
                messageCount++;
            }

            int strongCount = countValidators(strongHead);
            int weakCount = countValidators(weakHead);
            long[] messageIds = new long[messageCount];
            String[] strongValidators = new String[strongCount];
            String[] weakValidators = new String[weakCount];

            int index = 0;
            for (MessageNode node = head; node != null; node = node.next) {
                messageIds[index++] = node.id;
            }
            copyValidators(strongHead, strongValidators);
            copyValidators(weakHead, weakValidators);

            return new Snapshot(capacity, occupancy, messageIds,
                    strongValidators, weakValidators);
        }
    }

    private void removeHead() {
        MessageNode removed = head;
        head = removed.next;
        if (head == null) {
            tail = null;
        }
        occupancy -= removed.content.length;
        removeValidator(removed.draft, removed.validator);
    }

    private void appendValidator(boolean draft, String validator) {
        ValidatorNode node = new ValidatorNode(validator);
        if (draft) {
            if (weakTail == null) {
                weakHead = node;
            } else {
                weakTail.next = node;
            }
            weakTail = node;
        } else {
            if (strongTail == null) {
                strongHead = node;
            } else {
                strongTail.next = node;
            }
            strongTail = node;
        }
    }

    private void removeValidator(boolean draft, String validator) {
        ValidatorNode previous = null;
        ValidatorNode current = draft ? weakHead : strongHead;

        while (current != null) {
            if (current.value.equals(validator)) {
                if (previous == null) {
                    if (draft) {
                        weakHead = current.next;
                    } else {
                        strongHead = current.next;
                    }
                } else {
                    previous.next = current.next;
                }

                if (current.next == null) {
                    if (draft) {
                        weakTail = previous;
                    } else {
                        strongTail = previous;
                    }
                }
                return;
            }
            previous = current;
            current = current.next;
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

    private static int countValidators(ValidatorNode node) {
        int count = 0;
        while (node != null) {
            count++;
            node = node.next;
        }
        return count;
    }

    private static void copyValidators(ValidatorNode node, String[] destination) {
        int index = 0;
        while (node != null) {
            destination[index++] = node.value;
            node = node.next;
        }
    }

    public static final class Message {
        public final long messageId;
        public final byte[] content;
        public final boolean draft;
        public final String validator;

        private Message(long messageId, byte[] content, boolean draft, String validator) {
            this.messageId = messageId;
            this.content = content.clone();
            this.draft = draft;
            this.validator = validator;
        }
    }

    public static final class Snapshot {
        public final long capacity;
        public final long occupancy;
        public final long[] messageIds;
        public final String[] strongValidators;
        public final String[] weakValidators;

        private Snapshot(long capacity, long occupancy, long[] messageIds,
                         String[] strongValidators, String[] weakValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
            this.weakValidators = weakValidators;
        }
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

    private static final class ValidatorNode {
        private final String value;
        private ValidatorNode next;

        private ValidatorNode(String value) {
            this.value = value;
        }
    }
}
