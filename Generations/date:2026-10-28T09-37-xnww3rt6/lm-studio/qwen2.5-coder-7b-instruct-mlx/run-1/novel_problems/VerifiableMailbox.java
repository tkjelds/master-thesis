@VerifiableMailbox.ThreadSafe
public class VerifiableMailbox {
    @interface ThreadSafe {}

    private final byte[] content;
    private final boolean[] isDraft;
    private final String[] validators;
    private int head;
    private int tail;
    private long nextId;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.content = new byte[capacity];
        this.isDraft = new boolean[capacity];
        this.validators = new String[capacity];
        this.head = 0;
        this.tail = 0;
        this.nextId = 1;
    }

    private synchronized long receive(byte[] content, boolean draft) {
        if (content == null || content.length == 0 || content.length > this.content.length) return -1;
        long id = this.nextId++;
        while (true) {
            int pos = this.head;
            boolean foundDuplicate = false;
            for (int i = 0; i < this.tail - pos; i++) {
                if (!this.isDraft[pos] == draft) continue;
                int j = 0;
                while (j < content.length && this.content[pos + i] == content[j]) j++;
                if (j == content.length) {
                    foundDuplicate = true;
                    break;
                }
            }
            if (foundDuplicate) return -1;
            int remainingCapacity = this.tail == pos ? 0 : this.head + this.content.length - pos;
            if (remainingCapacity < content.length) {
                int shift = remainingCapacity + this.tail - pos;
                System.arraycopy(this.content, pos, this.content, 0, shift);
                System.arraycopy(this.isDraft, pos, this.isDraft, 0, shift);
                System.arraycopy(this.validators, pos, this.validators, 0, shift);
                head = 0;
            }
            System.arraycopy(content, 0, this.content, this.tail, content.length);
            this.isDraft[this.tail] = draft;
            this.validators[this.tail] = draft ? "W-" + id : "S-" + id;
            this.tail += content.length;
            return id;
        }
    }

    private synchronized VerifiableMessage fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0 || validator == null) return null;
        for (int i = head; i < tail; i++) {
            if (this.validators[i].equals(validator) && this.isDraft[i] == messageId % 2 == 0) {
                return new VerifiableMessage(new byte[content.length], head, tail - i);
            }
        }
        return null;
    }

    private synchronized int prune(long throughId) {
        if (throughId < 0) return 0;
        int removed = 0;
        for (int i = head; i < tail && this.validators[i].startsWith("S-" + throughId); i++) {
            removed++;
        }
        if (removed > 0) {
            System.arraycopy(this.content, tail - removed, this.content, head, tail - (tail - removed));
            System.arraycopy(this.isDraft, tail - removed, this.isDraft, head, tail - (tail - removed));
            System.arraycopy(this.validators, tail - removed, this.validators, head, tail - (tail - removed));
            tail -= removed;
        }
        return removed;
    }

    private synchronized VerifiableMailboxSnapshot snapshotHeader() {
        int totalOccupancy = 0;
        for (int i = head; i < tail; i++) {
            totalOccupancy += this.content.length;
        }
        int[] messageIds = new int[tail - head];
        String[] strongValidators = new String[tail - head];
        for (int i = 0, j = head; j < tail; i++, j++) {
            messageIds[i] = j;
            strongValidators[i] = this.validators[j];
        }
        return new VerifiableMailboxSnapshot(this.content.length, totalOccupancy, messageIds, strongValidators);
    }

    public static class VerifiableMessage {
        private final byte[] content;
        private int offset;
        private int remaining;

        public VerifiableMessage(byte[] content, int startOffset, int length) {
            this.content = content;
            this.offset = startOffset;
            this.remaining = length;
        }

        public byte[] getContent() {
            byte[] copy = new byte[remaining];
            System.arraycopy(this.content, offset, copy, 0, remaining);
            return copy;
        }
    }

    public static class VerifiableMailboxSnapshot {
        private final int capacity;
        private final int occupancy;
        private final int[] messageIds;
        private final String[] strongValidators;

        public VerifiableMailboxSnapshot(int capacity, int occupancy, int[] messageIds, String[] strongValidators) {
            this.capacity = capacity;
            this.occupancy = occupancy;
            this.messageIds = messageIds;
            this.strongValidators = strongValidators;
        }

        public int getCapacity() {
            return capacity;
        }

        public int getOccupancy() {
            return occupancy;
        }

        public int[] getMessageIds() {
            return messageIds;
        }

        public String[] getStrongValidators() {
            return strongValidators;
        }
    }
}
