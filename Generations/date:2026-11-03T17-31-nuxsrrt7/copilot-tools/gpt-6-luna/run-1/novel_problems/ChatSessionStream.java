@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    private String[] users = new String[0];
    private int userCount;
    private Message[] messages = new Message[0];
    private int messageCount;
    private String history = "";
    private int cursor;
    private long sequence;
    private boolean closed;

    public boolean addUser(String user) {
        validateUser(user);
        synchronized (lock) {
            ensureOpen();
            if (findUser(user) >= 0) {
                return false;
            }
            if (userCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("User capacity exhausted");
            }
            String[] updatedUsers = grow(users, userCount + 1);
            updatedUsers[userCount] = user;
            users = updatedUsers;
            userCount++;
            return true;
        }
    }

    public boolean removeUser(String user) {
        validateUser(user);
        synchronized (lock) {
            ensureOpen();
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
        validateUser(user);
        validateText(text);
        synchronized (lock) {
            ensureOpen();
            if (findUser(user) < 0) {
                throw new IllegalArgumentException("User is not registered");
            }
            return appendMessage(user, text);
        }
    }

    public long write(String command) {
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
        synchronized (lock) {
            ensureOpen();
            if (findUser(user) < 0) {
                throw new IllegalArgumentException("User is not registered");
            }
            return appendMessage(user, text);
        }
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        synchronized (lock) {
            int count = history.length() - cursor;
            if (count > maxChars) {
                count = maxChars;
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
                Message message = messages[i];
                messageCopy[i] = new Message(
                    message.getSequence(), message.getUser(), message.getText());
            }
            return new Snapshot(
                userCopy, messageCopy, history, cursor, sequence, closed);
        }
    }

    private long appendMessage(String user, String text) {
        if (sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Sequence numbers exhausted");
        }
        if (messageCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("Message capacity exhausted");
        }

        long nextSequence = sequence + 1;
        Message message = new Message(nextSequence, user, text);
        String nextHistory = history + user + ": " + text + "\n";
        Message[] updatedMessages = grow(messages, messageCount + 1);

        updatedMessages[messageCount] = message;
        messages = updatedMessages;
        history = nextHistory;
        messageCount++;
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

    private static String[] grow(String[] current, int required) {
        if (current.length >= required) {
            return current;
        }
        int capacity = current.length == 0 ? 4 : current.length;
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = required;
                break;
            }
            capacity *= 2;
        }
        String[] result = new String[capacity];
        for (int i = 0; i < current.length; i++) {
            result[i] = current[i];
        }
        return result;
    }

    private static Message[] grow(Message[] current, int required) {
        if (current.length >= required) {
            return current;
        }
        int capacity = current.length == 0 ? 4 : current.length;
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = required;
                break;
            }
            capacity *= 2;
        }
        Message[] result = new Message[capacity];
        for (int i = 0; i < current.length; i++) {
            result[i] = current[i];
        }
        return result;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User must be nonempty");
        }
        for (int i = 0; i < user.length(); i++) {
            char character = user.charAt(i);
            if (character == ':' || character == '\r' || character == '\n') {
                throw new IllegalArgumentException("Invalid user");
            }
        }
    }

    private static void validateText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text must not be null");
        }
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\r' || character == '\n') {
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

        private Snapshot(
            String[] users,
            Message[] messages,
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
            for (int i = 0; i < users.length; i++) {
                copy[i] = users[i];
            }
            return copy;
        }

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                Message message = messages[i];
                copy[i] = new Message(
                    message.getSequence(), message.getUser(), message.getText());
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
