@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    private String[] users = new String[4];
    private int userCount;

    private MessageRecord[] messages = new MessageRecord[4];
    private int messageCount;

    private char[] history = new char[16];
    private int historyLength;
    private int cursor;
    private long sequence;
    private boolean closed;

    public ChatSessionStream() {
    }

    public boolean addUser(String user) {
        synchronized (lock) {
            ensureOpen();
            validateUser(user);

            if (findUser(user) >= 0) {
                return false;
            }
            if (userCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("User capacity exhausted");
            }

            ensureUserCapacity(userCount + 1);
            users[userCount++] = user;
            return true;
        }
    }

    public boolean removeUser(String user) {
        synchronized (lock) {
            ensureOpen();
            validateUser(user);

            int index = findUser(user);
            if (index < 0) {
                return false;
            }

            for (int i = index; i < userCount - 1; i++) {
                users[i] = users[i + 1];
            }
            users[--userCount] = null;
            return true;
        }
    }

    public long send(String user, String text) {
        synchronized (lock) {
            ensureOpen();
            validateUser(user);
            validateText(text);
            return sendLocked(user, text);
        }
    }

    public long write(String command) {
        synchronized (lock) {
            ensureOpen();
            if (command == null) {
                throw new NullPointerException("command");
            }

            int delimiter = command.indexOf(": ");
            if (delimiter < 0) {
                throw new IllegalArgumentException("Malformed command");
            }

            String user = command.substring(0, delimiter);
            String text = command.substring(delimiter + 2);
            validateUser(user);
            validateText(text);
            return sendLocked(user, text);
        }
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }

        synchronized (lock) {
            int count = historyLength - cursor;
            if (count > maxChars) {
                count = maxChars;
            }

            String result = new String(history, cursor, count);
            cursor += count;
            return result;
        }
    }

    public void seek(int position) {
        synchronized (lock) {
            if (position < 0 || position > historyLength) {
                throw new IllegalArgumentException("Position is outside the history");
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
            String[] userCopy = new String[userCount];
            for (int i = 0; i < userCount; i++) {
                userCopy[i] = users[i];
            }

            MessageRecord[] messageCopy = new MessageRecord[messageCount];
            for (int i = 0; i < messageCount; i++) {
                messageCopy[i] = messages[i];
            }

            String textCopy = new String(history, 0, historyLength);
            return new Snapshot(
                    userCopy, messageCopy, textCopy, cursor, sequence, closed);
        }
    }

    private long sendLocked(String user, String text) {
        if (findUser(user) < 0) {
            throw new IllegalArgumentException("User is not registered");
        }
        if (sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Sequence numbers exhausted");
        }
        if (messageCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("Message capacity exhausted");
        }

        long lineLengthLong = (long) user.length() + 2L + text.length() + 1L;
        long requiredHistoryLength = (long) historyLength + lineLengthLong;
        if (lineLengthLong > Integer.MAX_VALUE
                || requiredHistoryLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("History capacity exhausted");
        }

        int lineLength = (int) lineLengthLong;
        int requiredHistory = (int) requiredHistoryLength;
        char[] line = new char[lineLength];

        int offset = 0;
        user.getChars(0, user.length(), line, offset);
        offset += user.length();
        line[offset++] = ':';
        line[offset++] = ' ';
        text.getChars(0, text.length(), line, offset);
        offset += text.length();
        line[offset] = '\n';

        long nextSequence = sequence + 1L;
        MessageRecord record = new MessageRecord(nextSequence, user, text);

        ensureMessageCapacity(messageCount + 1);
        ensureHistoryCapacity(requiredHistory);

        messages[messageCount] = record;
        System.arraycopy(line, 0, history, historyLength, lineLength);
        messageCount++;
        historyLength = requiredHistory;
        sequence = nextSequence;

        return nextSequence;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
    }

    private int findUser(String user) {
        for (int i = 0; i < userCount; i++) {
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
        if (user.length() == 0) {
            throw new IllegalArgumentException("Usernames must be nonempty");
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("Invalid username");
            }
        }
    }

    private static void validateText(String text) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("Invalid message text");
            }
        }
    }

    private void ensureUserCapacity(int required) {
        if (required <= users.length) {
            return;
        }

        int newCapacity = expandedCapacity(users.length, required);
        String[] replacement = new String[newCapacity];
        System.arraycopy(users, 0, replacement, 0, userCount);
        users = replacement;
    }

    private void ensureMessageCapacity(int required) {
        if (required <= messages.length) {
            return;
        }

        int newCapacity = expandedCapacity(messages.length, required);
        MessageRecord[] replacement = new MessageRecord[newCapacity];
        System.arraycopy(messages, 0, replacement, 0, messageCount);
        messages = replacement;
    }

    private void ensureHistoryCapacity(int required) {
        if (required <= history.length) {
            return;
        }

        int newCapacity = expandedCapacity(history.length, required);
        char[] replacement = new char[newCapacity];
        System.arraycopy(history, 0, replacement, 0, historyLength);
        history = replacement;
    }

    private static int expandedCapacity(int current, int required) {
        long candidate = current == 0 ? 4L : (long) current * 2L;
        if (candidate < required) {
            candidate = required;
        }
        if (candidate > Integer.MAX_VALUE) {
            candidate = Integer.MAX_VALUE;
        }
        return (int) candidate;
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
        public final String text;
        public final int cursor;
        public final long sequence;
        public final boolean closed;

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
            String[] copy = new String[users.length];
            System.arraycopy(users, 0, copy, 0, users.length);
            return copy;
        }

        public MessageRecord[] getMessages() {
            MessageRecord[] copy = new MessageRecord[messages.length];
            System.arraycopy(messages, 0, copy, 0, messages.length);
            return copy;
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
