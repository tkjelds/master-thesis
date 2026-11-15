@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private String[] users = new String[0];
    private int userCount;
    private MessageRecord[] messages = new MessageRecord[8];
    private int messageCount;
    private final StringBuilder history = new StringBuilder();
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
            if (userCount == users.length) {
                int newCapacity = users.length == 0 ? 4 : users.length * 2;
                String[] expanded = new String[newCapacity];
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
            if (findUser(user) < 0) {
                throw new IllegalArgumentException("User is not registered");
            }
            return appendMessage(user, text);
        }
    }

    public long write(String command) {
        synchronized (lock) {
            ensureOpen();
            if (command == null) {
                throw new IllegalArgumentException("Command must not be null");
            }
            int delimiter = command.indexOf(": ");
            if (delimiter <= 0) {
                throw new IllegalArgumentException("Malformed command");
            }
            String user = command.substring(0, delimiter);
            String text = command.substring(delimiter + 2);
            validateUser(user);
            validateText(text);
            if (findUser(user) < 0) {
                throw new IllegalArgumentException("User is not registered");
            }
            return appendMessage(user, text);
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
            return new Snapshot(
                userCopy,
                messageCopy,
                history.toString(),
                cursor,
                sequence,
                closed
            );
        }
    }

    private long appendMessage(String user, String text) {
        if (sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Message sequence is exhausted");
        }
        long nextSequence = sequence + 1;
        String rendered = user + ": " + text + "\n";

        if (messageCount == messages.length) {
            int newCapacity = messages.length * 2;
            MessageRecord[] expanded = new MessageRecord[newCapacity];
            for (int i = 0; i < messageCount; i++) {
                expanded[i] = messages[i];
            }
            messages = expanded;
        }

        messages[messageCount] = new MessageRecord(nextSequence, user, text);
        messageCount++;
        history.append(rendered);
        sequence = nextSequence;
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
            throw new IllegalStateException("Session is closed");
        }
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User must be nonempty");
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("Invalid user");
            }
        }
    }

    private static void validateText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text must not be null");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("Invalid text");
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
            boolean closed
        ) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }

        public String[] getUsers() {
            String[] copy = new String[users.length];
            for (int i = 0; i < users.length; i++) {
                copy[i] = users[i];
            }
            return copy;
        }

        public MessageRecord[] getMessages() {
            MessageRecord[] copy = new MessageRecord[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copy[i] = messages[i];
            }
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
