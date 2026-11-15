@VerifiableMailbox.ThreadSafe
public class VerifiableMailbox {
    @interface ThreadSafe {}

    private final int capacity;
    private byte[] content;
    private long messageId;
    private String[] strongValidators;
    private String[] weakValidators;

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.content = new byte[capacity];
        this.messageId = 0;
        this.strongValidators = new String[capacity];
        this.weakValidators = new String[capacity];
    }

    public synchronized long receive(byte[] content, boolean draft) {
        if (content == null || content.length <= 0 || content.length > capacity) return -1;
        byte[] copyContent = new byte[content.length];
        System.arraycopy(content, 0, copyContent, 0, content.length);

        while (true) {
            boolean duplicate = false;
            for (int i = 0; i < messageId; i++) {
                if (strongValidators[i].equals(draft ? "W-" + i : "S-" + i) &&
                    isSameContent(copyContent, this.content[i])) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) break;

            int newId = messageId++;
            if (newId >= capacity) throw new IllegalStateException("ID exhaustion");
            System.arraycopy(copyContent, 0, this.content[i], 0, content.length);
            strongValidators[newId] = draft ? "W-" + newId : "S-" + newId;
            weakValidators[newId] = draft ? "W-" + newId : "S-" + newId;
            return newId;
        }
        return -1;
    }

    public synchronized byte[] fetchIfMatch(long messageId, String validator) {
        if (messageId <= 0 || validator == null) return null;
        if (!validator.startsWith(draft ? "W-" : "S-") || !isSameContent(validator.substring(2), strongValidators[messageId - 1])) return null;
        byte[] copyContent = new byte[this.content[messageId - 1].length];
        System.arraycopy(this.content[messageId - 1], 0, copyContent, 0, this.content[messageId - 1].length);
        return copyContent;
    }

    public synchronized int prune(long throughId) {
        if (throughId < 0) throw new IllegalArgumentException("Through ID must be nonnegative");
        int removed = 0;
        while (messageId > 0 && messageId - 1 <= throughId) {
            System.arraycopy(content, 1, content, 0, messageId - 1);
            strongValidators[messageId - 1] = null;
            weakValidators[messageId - 1] = null;
            messageId--;
            removed++;
        }
        return removed;
    }

    public synchronized Object[] snapshotHeader() {
        Object[] header = new Object[3 + messageId];
        header[0] = capacity;
        header[1] = messageId;
        for (int i = 0; i < messageId; i++) {
            header[2 + i] = strongValidators[i];
        }
        return header;
    }

    private boolean isSameContent(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private boolean isSameContent(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
