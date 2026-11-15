@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private String[] users = new String[0];
    private Message[] messages = new Message[0];
    private int messageCount;
    private String history = "";
    private int cursor;
    private long currentSequence;
    private boolean closed;

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
        public final long currentSequence;
        public final boolean closed;

        private Snapshot(String[] users, Message[] messages, String text,
                         int cursor, long currentSequence, boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.currentSequence = currentSequence;
            this.closed = closed;
        }
    }

    public boolean addUser(String user) {
        validateUser(user);
        synchronized (this) {
            ensureOpen();
            if (containsUser(user)) {
                return false;
            }

            String[] updatedUsers = new String[users.length + 1];
            for (int i = 0; i < users.length; i++) {
                updatedUsers[i] = users[i];
            }
            updatedUsers[users.length] = user;
            users = updatedUsers;
            return true;
        }
    }

    public boolean removeUser(String user) {
        validateUser(user);
        synchronized (this) {
            ensureOpen();
            int index = indexOfUser(user);
            if (index < 0) {
                return false;
            }

            String[] updatedUsers = new String[users.length - 1];
            for (int i = 0; i < index; i++) {
                updatedUsers[i] = users[i];
            }
            for (int i = index + 1; i < users.length; i++) {
                updatedUsers[i - 1] = users[i];
            }
            users = updatedUsers;
            return true;
        }
    }

    public long send(String user, String text) {
        validateUser(user);
        validateText(text);
        synchronized (this) {
            ensureOpen();
            if (!containsUser(user)) {
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
        if (delimiter < 0) {
            throw new IllegalArgumentException("Malformed command");
        }

        String user = command.substring(0, delimiter);
        String text = command.substring(delimiter + 2);
        validateUser(user);
        validateText(text);

        synchronized (this) {
            ensureOpen();
            if (!containsUser(user)) {
                throw new IllegalArgumentException("User is not registered");
            }
            return appendMessage(user, text);
        }
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        synchronized (this) {
            int end = cursor + Math.min(maxChars, history.length() - cursor);
            String result = history.substring(cursor, end);
            cursor = end;
            return result;
        }
    }

    public void seek(int position) {
        synchronized (this) {
            if (position < 0 || position > history.length()) {
                throw new IllegalArgumentException("Position is outside the history");
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
            String[] copiedUsers = new String[users.length];
            for (int i = 0; i < users.length; i++) {
                copiedUsers[i] = users[i];
            }

            Message[] copiedMessages = new Message[messageCount];
            for (int i = 0; i < messageCount; i++) {
                copiedMessages[i] = messages[i];
            }

            return new Snapshot(copiedUsers, copiedMessages, history, cursor,
                                currentSequence, closed);
        }
    }

    private long appendMessage(String user, String text) {
        if (currentSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Sequence numbers are exhausted");
        }
        if (messageCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("Message history is too large");
        }

        long nextSequence = currentSequence + 1;
        Message message = new Message(nextSequence, user, text);
        String updatedHistory = history + user + ": " + text + "\n";
        Message[] updatedMessages = messages;

        if (messageCount == messages.length) {
            int newCapacity;
            if (messages.length == 0) {
                newCapacity = 4;
            } else if (messages.length <= Integer.MAX_VALUE / 2) {
                newCapacity = messages.length * 2;
            } else {
                newCapacity = Integer.MAX_VALUE;
            }
            if (newCapacity <= messageCount) {
                throw new IllegalStateException("Message history is too large");
            }

            updatedMessages = new Message[newCapacity];
            for (int i = 0; i < messageCount; i++) {
                updatedMessages[i] = messages[i];
            }
            updatedMessages[messageCount] = message;
        } else {
            updatedMessages[messageCount] = message;
        }

        messages = updatedMessages;
        messageCount++;
        history = updatedHistory;
        currentSequence = nextSequence;
        return nextSequence;
    }

    private boolean containsUser(String user) {
        return indexOfUser(user) >= 0;
    }

    private int indexOfUser(String user) {
        for (int i = 0; i < users.length; i++) {
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
                throw new IllegalArgumentException("Invalid message text");
            }
        }
    }
}
