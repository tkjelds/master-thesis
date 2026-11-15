@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    private String[] users = new String[0];
    private int userCount;

    private MessageRecord[] messages = new MessageRecord[0];
    private int messageCount;

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

            if (userCount == users.length) {
                String[] expanded = new String[grownCapacity(users.length)];
                for (int i = 0; i < userCount; i++) {
                    expanded[i] = users[i];
                }
                users = expanded;
            }

            users[userCount++] = user;
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

            for (int i = index; i < userCount - 1; i++) {
                users[i] = users[i + 1];
            }
            users[--userCount] = null;
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
            if (delimiter < 0) {
                throw new IllegalArgumentException("Malformed command");
            }

            String user = command.substring(0, delimiter);
            String text = command.substring(delimiter + 2);
            return sendLocked(user, text);
        }
    }

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
                throw new IllegalArgumentException("Invalid position");
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

            return new Snapshot(
                    userCopy, messageCopy, history, cursor, sequence, closed);
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

        long newLength = (long) history.length()
                + user.length() + text.length() + 3L;
        if (newLength > 2147483647L) {
            throw new IllegalStateException("Text history is too long");
        }

        long nextSequence = sequence + 1;
        MessageRecord record = new MessageRecord(nextSequence, user, text);
        String newHistory = history + user + ": " + text + "\n";

        MessageRecord[] target = messages;
        if (messageCount == messages.length) {
            target = new MessageRecord[grownCapacity(messages.length)];
            for (int i = 0; i < messageCount; i++) {
                target[i] = messages[i];
            }
        }

        target[messageCount] = record;
        messages = target;
        messageCount++;
        history = newHistory;
        sequence = nextSequence;
        return nextSequence;
    }

    private void requireOpen() {
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
            throw new IllegalArgumentException("Empty username");
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

    private static int grownCapacity(int current) {
        if (current == 0) {
            return 4;
        }
        if (current == 2147483647) {
            throw new IllegalStateException("Capacity exhausted");
        }
        if (current > 1073741823) {
            return 2147483647;
        }
        return current * 2;
    }

    public static final class MessageRecord {
        private final long sequence;
        private final String user;
        private final String text;

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

        private Snapshot(String[] users, MessageRecord[] messages, String text,
                         int cursor, long sequence, boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }

        public String[] getUsers() {
            return users.clone();
        }

        public MessageRecord[] getMessages() {
            return messages.clone();
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
