@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    private String[] users = new String[4];
    private int userCount;

    private Message[] messages = new Message[4];
    private int messageCount;

    private String history = "";
    private int cursor;
    private long sequence;
    private boolean closed;

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

            String[] updatedUsers = users;
            if (userCount == users.length) {
                updatedUsers = growUsers(userCount + 1);
            }

            updatedUsers[userCount] = user;
            users = updatedUsers;
            userCount++;
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
            return sendLocked(user, text);
        }
    }

    public String read(int maxChars) {
        synchronized (lock) {
            if (maxChars <= 0) {
                throw new IllegalArgumentException("maxChars must be positive");
            }

            int count = Math.min(maxChars, history.length() - cursor);
            if (count == 0) {
                return "";
            }

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

            Message[] messageCopy = new Message[messageCount];
            for (int i = 0; i < messageCount; i++) {
                messageCopy[i] = messages[i];
            }

            return new Snapshot(userCopy, messageCopy, history, cursor, sequence, closed);
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

        long lineLength = (long) user.length() + 2L + text.length() + 1L;
        long historyLength = (long) history.length() + lineLength;
        if (historyLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("History capacity exhausted");
        }

        String line = user + ": " + text + "\n";
        String updatedHistory = history + line;
        long nextSequence = sequence + 1;
        Message message = new Message(nextSequence, user, text);

        Message[] updatedMessages = messages;
        if (messageCount == messages.length) {
            updatedMessages = growMessages(messageCount + 1);
        }

        updatedMessages[messageCount] = message;
        messages = updatedMessages;
        history = updatedHistory;
        sequence = nextSequence;
        messageCount++;
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

    private String[] growUsers(int required) {
        if (users.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("User capacity exhausted");
        }
        int capacity = users.length == 0 ? 4
                : (users.length <= Integer.MAX_VALUE / 2 ? users.length * 2 : Integer.MAX_VALUE);
        if (capacity < required) {
            capacity = required;
        }

        String[] result = new String[capacity];
        for (int i = 0; i < userCount; i++) {
            result[i] = users[i];
        }
        return result;
    }

    private Message[] growMessages(int required) {
        if (messages.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("Message capacity exhausted");
        }
        int capacity = messages.length == 0 ? 4
                : (messages.length <= Integer.MAX_VALUE / 2 ? messages.length * 2 : Integer.MAX_VALUE);
        if (capacity < required) {
            capacity = required;
        }

        Message[] result = new Message[capacity];
        for (int i = 0; i < messageCount; i++) {
            result[i] = messages[i];
        }
        return result;
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
                throw new IllegalArgumentException("Invalid message text");
            }
        }
    }

    public static final class Message {
        private final long sequence;
        private final String user;
        private final String text;

        private Message(long sequence, String user, String text) {
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
        private final Message[] messages;
        private final String text;
        private final int cursor;
        private final long sequence;
        private final boolean closed;

        private Snapshot(String[] users, Message[] messages, String text, int cursor,
                         long sequence, boolean closed) {
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

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
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
