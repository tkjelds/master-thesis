@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private String[] users = new String[0];
    private int userCount;
    private Message[] messages = new Message[0];
    private int messageCount;
    private char[] history = new char[0];
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
                throw new IllegalStateException("User membership is too large");
            }
            int needed = userCount + 1;
            String[] updatedUsers = users;
            if (needed > users.length) {
                updatedUsers = new String[grownCapacity(users.length, needed)];
                copy(users, updatedUsers, userCount);
            }
            updatedUsers[userCount] = user;
            users = updatedUsers;
            userCount = needed;
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
                throw new NullPointerException("command");
            }
            int delimiter = -1;
            for (int i = 0; i + 1 < command.length(); i++) {
                if (command.charAt(i) == ':' && command.charAt(i + 1) == ' ') {
                    delimiter = i;
                    break;
                }
            }
            if (delimiter < 0) {
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
            copy(users, userCopy, userCount);
            Message[] messageCopy = new Message[messageCount];
            copy(messages, messageCopy, messageCount);
            return new Snapshot(
                userCopy,
                messageCopy,
                new String(history, 0, historyLength),
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

        long lineLength = (long) user.length() + 2L + text.length() + 1L;
        long newHistoryLength = (long) historyLength + lineLength;
        if (newHistoryLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("History is too large");
        }
        if (messageCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("Message history is too large");
        }

        int newLength = (int) newHistoryLength;
        int newCount = messageCount + 1;
        char[] updatedHistory = history;
        if (newLength > history.length) {
            updatedHistory = new char[grownCapacity(history.length, newLength)];
            copy(history, updatedHistory, historyLength);
        }
        Message[] updatedMessages = messages;
        if (newCount > messages.length) {
            updatedMessages = new Message[grownCapacity(messages.length, newCount)];
            copy(messages, updatedMessages, messageCount);
        }

        long assignedSequence = sequence + 1;
        Message message = new Message(assignedSequence, user, text);
        int index = historyLength;
        index = copyString(user, updatedHistory, index);
        updatedHistory[index++] = ':';
        updatedHistory[index++] = ' ';
        index = copyString(text, updatedHistory, index);
        updatedHistory[index] = '\n';
        updatedMessages[messageCount] = message;

        history = updatedHistory;
        historyLength = newLength;
        messages = updatedMessages;
        messageCount = newCount;
        sequence = assignedSequence;
        return assignedSequence;
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
            throw new IllegalArgumentException("Username must not be empty");
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
                throw new IllegalArgumentException("Message text must not contain line breaks");
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
    }

    private static int grownCapacity(int current, int needed) {
        long capacity = current == 0 ? 4L : (long) current * 2L;
        if (capacity < needed) {
            capacity = needed;
        }
        if (capacity > Integer.MAX_VALUE) {
            capacity = Integer.MAX_VALUE;
        }
        if (capacity < needed) {
            throw new IllegalStateException("Capacity is exhausted");
        }
        return (int) capacity;
    }

    private static int copyString(String source, char[] destination, int offset) {
        for (int i = 0; i < source.length(); i++) {
            destination[offset++] = source.charAt(i);
        }
        return offset;
    }

    private static void copy(String[] source, String[] destination, int count) {
        for (int i = 0; i < count; i++) {
            destination[i] = source[i];
        }
    }

    private static void copy(Message[] source, Message[] destination, int count) {
        for (int i = 0; i < count; i++) {
            destination[i] = source[i];
        }
    }

    private static void copy(char[] source, char[] destination, int count) {
        for (int i = 0; i < count; i++) {
            destination[i] = source[i];
        }
    }

    public static final class Message {
        public final long sequence;
        public final String user;
        public final String text;

        private Message(long sequence, String user, String text) {
            this.sequence = sequence;
            this.user = user;
            this.text = text;
        }
    }

    public static final class Snapshot {
        public final String[] users;
        public final Message[] messages;
        public final String text;
        public final int cursor;
        public final long sequence;
        public final boolean closed;

        private Snapshot(
            String[] users,
            Message[] messages,
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
    }
}
