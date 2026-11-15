@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    private String[] users = new String[0];
    private MessageRecord[] messages = new MessageRecord[0];
    private String history = "";
    private int cursor;
    private long sequence;
    private boolean closed;

    public boolean addUser(String user) {
        synchronized (lock) {
            requireOpen();
            validateUser(user);

            if (findUser(user) >= 0) {
                return false;
            }

            String[] updated = new String[users.length + 1];
            for (int i = 0; i < users.length; i++) {
                updated[i] = users[i];
            }
            updated[users.length] = user;
            users = updated;
            return true;
        }
    }

    public boolean removeUser(String user) {
        synchronized (lock) {
            requireOpen();
            validateUser(user);

            int index = findUser(user);
            if (index < 0) {
                return false;
            }

            String[] updated = new String[users.length - 1];
            for (int i = 0; i < index; i++) {
                updated[i] = users[i];
            }
            for (int i = index + 1; i < users.length; i++) {
                updated[i - 1] = users[i];
            }
            users = updated;
            return true;
        }
    }

    public long send(String user, String text) {
        synchronized (lock) {
            requireOpen();
            return sendLocked(user, text);
        }
    }

    public long write(String command) {
        synchronized (lock) {
            requireOpen();
            if (command == null) {
                throw new NullPointerException("command");
            }

            int delimiter = command.indexOf(": ");
            if (delimiter <= 0) {
                throw new IllegalArgumentException(
                        "Command must have the form user + \": \" + text");
            }

            String user = command.substring(0, delimiter);
            String text = command.substring(delimiter + 2);
            return sendLocked(user, text);
        }
    }

    /**
     * Returns immediately, including when no characters are available.
     */
    public String read(int maxChars) {
        synchronized (lock) {
            if (maxChars <= 0) {
                throw new IllegalArgumentException("maxChars must be positive");
            }

            int available = history.length() - cursor;
            int count = maxChars < available ? maxChars : available;
            String result = history.substring(cursor, cursor + count);
            cursor += count;
            return result;
        }
    }

    public void seek(int position) {
        synchronized (lock) {
            if (position < 0 || position > history.length()) {
                throw new IndexOutOfBoundsException(
                        "Position must be within the current history");
            }
            cursor = position;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    copyUsers(users),
                    copyMessages(messages),
                    history,
                    cursor,
                    sequence,
                    closed);
        }
    }

    private long sendLocked(String user, String text) {
        validateUser(user);
        validateText(text);

        if (findUser(user) < 0) {
            throw new IllegalArgumentException("User is not registered");
        }
        if (sequence == 9223372036854775807L) {
            throw new IllegalStateException("Sequence numbers exhausted");
        }

        long nextSequence = sequence + 1;
        MessageRecord record = new MessageRecord(nextSequence, user, text);
        MessageRecord[] updated = new MessageRecord[messages.length + 1];
        for (int i = 0; i < messages.length; i++) {
            updated[i] = messages[i];
        }
        updated[messages.length] = record;

        String updatedHistory = history + user + ": " + text + "\n";

        // Complete all allocation before committing either history.
        messages = updated;
        history = updatedHistory;
        sequence = nextSequence;
        return nextSequence;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
    }

    private int findUser(String user) {
        for (int i = 0; i < users.length; i++) {
            if (users[i].equals(user)) {
                return i;
            }
        }
        return -1;
    }

    private static void validateUser(String user) {
        if (user == null) {
            throw new NullPointerException("user");
        }
        if (user.length() == 0
                || user.indexOf(':') >= 0
                || user.indexOf('\r') >= 0
                || user.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "User must be nonempty and contain no colon, CR, or LF");
        }
    }

    private static void validateText(String text) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Text must contain no CR or LF");
        }
    }

    private static String[] copyUsers(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static MessageRecord[] copyMessages(MessageRecord[] source) {
        MessageRecord[] result = new MessageRecord[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    public static final class MessageRecord {
        public final long sequence;
        public final String user;
        public final String text;

        private MessageRecord(long sequence, String user, String text) {
            this.sequence = sequence;
            this.user = user;
            this.text = text;
        }

        public long getSequence() {
            return sequence;
        }

        public String getUser() {
            return user;
        }

        public String getText() {
            return text;
        }
    }

    public static final class Snapshot {
        private final String[] users;
        private final MessageRecord[] messages;
        private final String text;
        private final int cursor;
        private final long sequence;
        private final boolean closed;

        private Snapshot(
                String[] users,
                MessageRecord[] messages,
                String text,
                int cursor,
                long sequence,
                boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }

        public String[] getUsers() {
            return copyUsers(users);
        }

        public MessageRecord[] getMessages() {
            return copyMessages(messages);
        }

        public String getText() {
            return text;
        }

        public int getCursor() {
            return cursor;
        }

        public long getSequence() {
            return sequence;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
