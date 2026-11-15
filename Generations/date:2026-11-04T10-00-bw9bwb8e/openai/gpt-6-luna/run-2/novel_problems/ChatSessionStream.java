@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private String[] users = new String[0];
    private int userCount;

    private MessageRecord[] messages = new MessageRecord[0];
    private int messageCount;

    private final StringBuilder history = new StringBuilder();
    private int cursor;
    private long currentSequence;
    private boolean closed;

    public boolean addUser(String user) {
        validateUser(user);
        synchronized (this) {
            ensureOpen();
            if (findUser(user) >= 0) {
                return false;
            }

            ensureUserCapacity(userCount + 1);
            users[userCount++] = user;
            return true;
        }
    }

    public boolean removeUser(String user) {
        validateUser(user);
        synchronized (this) {
            ensureOpen();
            int index = findUser(user);
            if (index < 0) {
                return false;
            }

            int remaining = userCount - index - 1;
            if (remaining > 0) {
                System.arraycopy(users, index + 1, users, index, remaining);
            }
            users[--userCount] = null;
            return true;
        }
    }

    public long send(String user, String text) {
        validateUser(user);
        validateText(text);
        synchronized (this) {
            ensureOpen();
            return appendMessage(user, text);
        }
    }

    public long write(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        int delimiter = -1;
        for (int i = 0; i + 1 < command.length(); i++) {
            if (command.charAt(i) == ':' && command.charAt(i + 1) == ' ') {
                delimiter = i;
                break;
            }
        }
        if (delimiter < 0) {
            throw new IllegalArgumentException("malformed command");
        }

        String user = command.substring(0, delimiter);
        String text = command.substring(delimiter + 2);
        validateUser(user);
        validateText(text);

        synchronized (this) {
            ensureOpen();
            return appendMessage(user, text);
        }
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        synchronized (this) {
            int available = history.length() - cursor;
            int count = Math.min(maxChars, available);
            String result = history.substring(cursor, cursor + count);
            cursor += count;
            return result;
        }
    }

    public void seek(int position) {
        synchronized (this) {
            if (position < 0 || position > history.length()) {
                throw new IllegalArgumentException("position is outside the history");
            }
            cursor = position;
        }
    }

    public void close() {
        synchronized (this) {
            closed = true;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            String[] userCopy = new String[userCount];
            System.arraycopy(users, 0, userCopy, 0, userCount);

            MessageRecord[] messageCopy = new MessageRecord[messageCount];
            System.arraycopy(messages, 0, messageCopy, 0, messageCount);

            return new Snapshot(
                userCopy,
                messageCopy,
                history.toString(),
                cursor,
                currentSequence,
                closed
            );
        }
    }

    private long appendMessage(String user, String text) {
        if (findUser(user) < 0) {
            throw new IllegalArgumentException("user is not registered");
        }
        if (currentSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("sequence numbers are exhausted");
        }

        long nextSequence = currentSequence + 1;
        MessageRecord record = new MessageRecord(nextSequence, user, text);
        ensureMessageCapacity(messageCount + 1);

        history.append(user).append(": ").append(text).append('\n');
        messages[messageCount++] = record;
        currentSequence = nextSequence;
        return nextSequence;
    }

    private int findUser(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].equals(user)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("session is closed");
        }
    }

    private void ensureUserCapacity(int required) {
        if (required <= users.length) {
            return;
        }
        int newCapacity = users.length == 0 ? 4 : users.length * 2;
        while (newCapacity < required) {
            newCapacity *= 2;
        }
        String[] expanded = new String[newCapacity];
        System.arraycopy(users, 0, expanded, 0, userCount);
        users = expanded;
    }

    private void ensureMessageCapacity(int required) {
        if (required <= messages.length) {
            return;
        }
        int newCapacity = messages.length == 0 ? 4 : messages.length * 2;
        while (newCapacity < required) {
            newCapacity *= 2;
        }
        MessageRecord[] expanded = new MessageRecord[newCapacity];
        System.arraycopy(messages, 0, expanded, 0, messageCount);
        messages = expanded;
    }

    private static void validateUser(String user) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        if (user.length() == 0) {
            throw new IllegalArgumentException("user must not be empty");
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("user contains a forbidden character");
            }
        }
    }

    private static void validateText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("text contains a forbidden character");
            }
        }
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
        public final String[] users;
        public final MessageRecord[] messages;
        public final String completeText;
        public final int cursor;
        public final long sequence;
        public final boolean closed;

        private Snapshot(
            String[] users,
            MessageRecord[] messages,
            String completeText,
            int cursor,
            long sequence,
            boolean closed
        ) {
            this.users = users;
            this.messages = messages;
            this.completeText = completeText;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }

        public String[] getUsers() {
            String[] copy = new String[users.length];
            System.arraycopy(users, 0, copy, 0, users.length);
            return copy;
        }

        public MessageRecord[] getMessages() {
            MessageRecord[] copy = new MessageRecord[messages.length];
            System.arraycopy(messages, 0, copy, 0, messages.length);
            return copy;
        }

        public String getCompleteText() {
            return completeText;
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
