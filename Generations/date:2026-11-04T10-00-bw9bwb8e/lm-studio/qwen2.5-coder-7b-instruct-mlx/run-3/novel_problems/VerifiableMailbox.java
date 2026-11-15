@VerifiableMailbox.ThreadSafe
public class VerifiableMailbox {
    @interface ThreadSafe {}

    private final byte[] capacity;
    private final Object lock = new Object();
    private byte[] messages;
    private long nextId;
    private Set<String> strongValidators;
    private Set<String> weakValidators;

    public VerifiableMailbox(int capacity) {
        this.capacity = new byte[capacity];
        this.messages = new byte[0];
        this.nextId = 1;
        this.strongValidators = new HashSet<>();
        this.weakValidators = new HashSet<>();
    }

    public long receive(byte[] content, boolean draft) {
        synchronized (lock) {
            if (content == null || content.length == 0 || content.length > capacity.length - messages.length) {
                return -1;
            }

            String validator = draft ? "W-" + nextId : "S-" + nextId;
            for (long i = 1; i < messages.length / content.length; i++) {
                if (Arrays.equals(messages, (int) i * content.length, (int) (i + 1) * content.length, content, 0, content.length)) {
                    return -1;
                }
            }

            while (messages.length + content.length > capacity.length) {
                byte[] newMessages = new byte[capacity.length - messages.length];
                System.arraycopy(messages, messages.length, newMessages, 0, capacity.length - messages.length);
                messages = newMessages;
            }

            byte[] newMessage = Arrays.copyOf(content, content.length);
            messages = Arrays.copyOf(messages, messages.length + content.length);
            System.arraycopy(newMessage, 0, messages, messages.length - newMessage.length, newMessage.length);

            strongValidators.add(validator);
            weakValidators.add(validator);

            return nextId++;
        }
    }

    public byte[] fetchIfMatch(long messageId, String validator) {
        synchronized (lock) {
            if (messageId <= 0 || validator == null) {
                return null;
            }

            byte[] message = Arrays.copyOfRange(messages, (int) ((messageId - 1) * messages.length / capacity.length), (int) messageId * messages.length / capacity.length);
            if (message != null && validator.equals("W-" + messageId) && weakValidators.contains(validator)) {
                return message;
            } else if (message != null && validator.equals("S-" + messageId) && strongValidators.contains(validator)) {
                return message;
            } else {
                return null;
            }
        }
    }

    public long prune(long throughId) {
        synchronized (lock) {
            if (throughId < 0) {
                return 0;
            }

            long removed = 0;
            byte[] newMessages = new byte[capacity.length];
            for (long i = throughId + 1; i < nextId; i++) {
                byte[] message = Arrays.copyOfRange(messages, (int) ((i - 1) * messages.length / capacity.length), (int) i * messages.length / capacity.length);
                if (message != null) {
                    removed += message.length;
                    System.arraycopy(message, 0, newMessages, newMessages.length, message.length);
                }
            }

            messages = newMessages;
            strongValidators.removeIf(validator -> validator.startsWith("W-" + throughId) || validator.startsWith("S-" + throughId));
            weakValidators.removeIf(validator -> validator.startsWith("W-" + throughId) || validator.startsWith("S-" + throughId));

            return removed;
        }
    }

    public long[] snapshotHeader() {
        synchronized (lock) {
            return new long[]{capacity.length, messages.length / capacity.length, nextId - 1};
        }
    }
}
