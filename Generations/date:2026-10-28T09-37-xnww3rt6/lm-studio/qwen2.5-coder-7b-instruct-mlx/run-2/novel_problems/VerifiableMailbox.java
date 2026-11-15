@VerifiableMailbox.ThreadSafe
class VerifiableMailbox {
    @interface ThreadSafe {}

    private final byte[] capacity;
    private final byte[] content;
    private final boolean[] isDraft;
    private final long[] messageId;
    private final Object lock = new Object();

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = new byte[capacity];
        this.content = new byte[capacity];
        this.isDraft = new boolean[capacity];
        this.messageId = new long[capacity];
    }

    public long receive(byte[] content, boolean draft) {
        synchronized (lock) {
            if (content == null || content.length <= 0 || content.length > this.capacity.length) {
                return -1;
            }

            long id = nextId();
            int offset = findEmptySlot();
            if (offset == -1) {
                while (true) {
                    offset = findOldestSlot();
                    if (offset == -1) {
                        throw new IllegalStateException("ID exhaustion");
                    }
                    if (this.content.length - offset < content.length) {
                        break;
                    }
                    System.arraycopy(this.capacity, 0, this.capacity, offset + content.length, offset);
                    System.arraycopy(this.content, 0, this.content, offset, offset);
                    System.arraycopy(this.isDraft, 0, this.isDraft, offset, offset);
                    System.arraycopy(this.messageId, 0, this.messageId, offset, offset);
                }
            }
            System.arraycopy(content, 0, this.content, offset, content.length);
            this.isDraft[offset] = draft;
            this.messageId[offset] = id;

            return id;
        }
    }

    public byte[] fetchIfMatch(long messageId, String validator) {
        synchronized (lock) {
            if (messageId <= 0 || validator == null) {
                return null;
            }

            for (int i = 0; i < this.messageId.length; i++) {
                if (this.messageId[i] == messageId && validator.equals(getValidator(i))) {
                    byte[] result = new byte[this.content.length];
                    System.arraycopy(this.content, i * this.content.length, result, 0, this.content.length);
                    return result;
                }
            }

            return null;
        }
    }

    public int prune(long throughId) {
        synchronized (lock) {
            if (throughId < 0) {
                throw new IllegalArgumentException("Through ID must be nonnegative");
            }

            int removed = 0;
            for (int i = 0; i < this.messageId.length; i++) {
                if (this.messageId[i] <= throughId) {
                    removed++;
                } else {
                    break;
                }
            }

            System.arraycopy(this.capacity, removed * this.content.length, this.capacity, 0, (this.messageId.length - removed) * this.content.length);
            System.arraycopy(this.content, removed * this.content.length, this.content, 0, (this.messageId.length - removed) * this.content.length);
            System.arraycopy(this.isDraft, removed * this.messageId.length, this.isDraft, 0, (this.messageId.length - removed) * this.messageId.length);
            System.arraycopy(this.messageId, removed * this.messageId.length, this.messageId, 0, (this.messageId.length - removed) * this.messageId.length);

            return removed;
        }
    }

    public String[] snapshotHeader() {
        synchronized (lock) {
            int occupancy = 0;
            for (int i = 0; i < this.messageId.length; i++) {
                if (this.messageId[i] != 0) {
                    occupancy++;
                }
            }

            long[] ids = new long[occupancy];
            int i = 0;
            for (int j = 0; j < this.messageId.length; j++) {
                if (this.messageId[j] != 0) {
                    ids[i++] = this.messageId[j];
                }
            }

            return new String[]{"capacity=" + capacity.length,
                    "occupancy=" + occupancy,
                    "messageIds=" + String.join(",", Long.toString(ids[0])),
                    "strongValidators=" + String.join(",", getStrongValidators()),
                    "weakValidators=" + String.join(",", getWeakValidators())};
        }
    }

    private long nextId() {
        synchronized (lock) {
            return messageId.length + 1;
        }
    }

    private int findEmptySlot() {
        for (int i = 0; i < messageId.length; i++) {
            if (messageId[i] == 0) {
                return i;
            }
        }

        return -1;
    }

    private int findOldestSlot() {
        for (int i = 0; i < messageId.length; i++) {
            if (messageId[i] != 0) {
                return i;
            }
        }

        return -1;
    }

    private String getValidator(int index) {
        if (isDraft[index]) {
            return "W-" + messageId[index];
        } else {
            return "S-" + messageId[index];
        }
    }

    private String[] getStrongValidators() {
        List<String> validators = new ArrayList<>();
        for (int i = 0; i < messageId.length; i++) {
            if (messageId[i] != 0 && !isDraft[i]) {
                validators.add(getValidator(i));
            }
        }

        return validators.toArray(new String[0]);
    }

    private String[] getWeakValidators() {
        List<String> validators = new ArrayList<>();
        for (int i = 0; i < messageId.length; i++) {
            if (messageId[i] != 0 && isDraft[i]) {
                validators.add(getValidator(i));
            }
        }

        return validators.toArray(new String[0]);
    }
}
