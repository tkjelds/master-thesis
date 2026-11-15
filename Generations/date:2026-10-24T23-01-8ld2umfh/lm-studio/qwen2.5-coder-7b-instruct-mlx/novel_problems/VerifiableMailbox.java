@VerifiableMailbox.ThreadSafe
public class VerifiableMailbox {
    @interface ThreadSafe {}

    private final byte[] capacity;
    private int occupancy = 0;
    private long nextId = 1;
    private final Object lock = new Object();
    private byte[][] messages = new byte[10][];
    private final String[] strongValidators = new String[10];
    private final String[] weakValidators = new String[10];

    public VerifiableMailbox(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = new byte[capacity];
    }

    public long receive(byte[] content, boolean draft) {
        synchronized (lock) {
            if (content == null || content.length == 0 || content.length > capacity.length - occupancy) {
                return -1;
            }
            for (int i = 0; i < nextId - 1; i++) {
                if (strongValidators[i] != null && strongValidators[i].equals("W-" + i) && messages[i].equals(content)) {
                    return -1;
                }
            }
            while (occupancy + content.length > capacity.length) {
                prune(0);
            }
            byte[] copy = new byte[content.length];
            System.arraycopy(content, 0, copy, 0, content.length);
            messages[nextId - 1] = copy;
            strongValidators[nextId - 1] = draft ? "W-" + (nextId - 1) : "S-" + (nextId - 1);
            weakValidators[nextId - 1] = draft ? "W-" + (nextId - 1) : "S-" + (nextId - 1);
            nextId++;
            occupancy += content.length;
            return nextId - 1;
        }
    }

    public byte[] fetchIfMatch(long messageId, String validator) {
        synchronized (lock) {
            if (messageId <= 0 || validator == null) return null;
            if ((draft ? strongValidators[messageId - 1] : weakValidators[messageId - 1]).equals(validator)) {
                return messages[messageId - 1].clone();
            }
            return null;
        }
    }

    public int prune(long throughId) {
        synchronized (lock) {
            if (throughId < 0) return 0;
            int removed = 0;
            for (int i = 0; i < nextId - 1; i++) {
                if (i <= throughId) {
                    strongValidators[i] = null;
                    weakValidators[i] = null;
                    messages[i] = null;
                    removed += strongValidators[i].length();
                }
            }
            nextId -= removed;
            occupancy -= removed;
            return removed;
        }
    }

    public String[] snapshotHeader() {
        synchronized (lock) {
            return new String[]{"Capacity", "Occupancy", "Message IDs", "Strong Validators", "Weak Validators"};
        }
    }
}
