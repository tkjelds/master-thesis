@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private String[] users = new String[0];
    private int userCount;

    private Message[] messages = new Message[0];
    private int messageCount;

    private String history = "";
    private int cursor;
    private long sequence;
    private boolean closed;

    public ChatSessionStream() {
    }

    public boolean addUser(String user) {
        synchronized (this) {
            ensureOpen();
            validateUser(user);
            if (indexOfUser(user) >= 0) {
                return false;
            }
            if (userCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("User capacity exhausted");
            }
            if (userCount == users.length) {
                String[] expanded = new String[grownCapacity(users.length, userCount + 1)];
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
        synchronized (this) {
            ensureOpen();
            validateUser(user);
            int index = indexOfUser(user);
            if (index < 0) {
                return false;
            }
            int remaining = userCount - index - 1;
            if (remaining > 0) {
                for (int i = 0; i < remaining; i++) {
                    users[index + i] = users[index + i + 1];
                }
            }
            users[--userCount] = null;
            return true;
        }
    }

    public long send(String user, String text) {
        synchronized (this) {
            ensureOpen();
            validateUser(user);
            validateText(text);
            if (indexOfUser(user) < 0) {
                throw new IllegalArgumentException("User is not registered");
            }
            if (sequence == Long.MAX_VALUE) {
                throw new IllegalStateException("Sequence numbers exhausted");
            }
            if (messageCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Message capacity exhausted");
            }

            long lineLength = (long) user.length() + 2L + text.length() + 1L;
            if ((long) history.length() + lineLength > Integer.MAX_VALUE) {
                throw new IllegalStateException("History capacity exhausted");
            }

            String line = user + ": " + text + "\n";
            String newHistory = history + line;
            Message message = new Message(sequence + 1L, user, text);

            Message[] destination = messages;
            if (messageCount == messages.length) {
                destination = new Message[grownCapacity(messages.length, messageCount + 1)];
                for (int i = 0; i < messageCount; i++) {
                    destination[i] = messages[i];
                }
            }

            destination[messageCount] = message;
            messages = destination;
            history = newHistory;
            messageCount++;
            sequence = message.sequence;
            return sequence;
        }
    }

    public long write(String command) {
        synchronized (this) {
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
            return send(user, text);
        }
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        synchronized (this) {
            int available = history.length() - cursor;
            int count = maxChars < available ? maxChars : available;
            String result = history.substring(cursor, cursor + count);
            cursor += count;
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
            String[] userCopy = new String[userCount];
            for (int i = 0; i < userCount; i++) {
                userCopy[i] = users[i];
            }

            Message[] messageCopy = new Message[messageCount];
            for (int i = 0; i < messageCount; i++) {
                Message message = messages[i];
                messageCopy[i] = new Message(message.sequence, message.user, message.text);
            }

            return new Snapshot(userCopy, messageCopy, history, cursor, sequence, closed);
        }
    }

    private int indexOfUser(String user) {
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

    private static int grownCapacity(int current, int minimum) {
        long candidate = current == 0 ? 4L : (long) current * 2L;
        if (candidate < minimum) {
            candidate = minimum;
        }
        if (candidate > Integer.MAX_VALUE) {
            candidate = Integer.MAX_VALUE;
        }
        if (candidate < minimum) {
            throw new IllegalStateException("Capacity exhausted");
        }
        return (int) candidate;
    }

    private static void validateUser(String user) {
        if (user == null) {
            throw new IllegalArgumentException("User must not be null");
        }
        if (user.length() == 0) {
            throw new IllegalArgumentException("User must not be empty");
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
        public final long sequence;
        public final String user;
        public final String text;

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
        public final String[] users;
        public final Message[] messages;
        public final String text;
        public final String history;
        public final int cursor;
        public final long sequence;
        public final long currentSequence;
        public final boolean closed;

        private Snapshot(String[] users, Message[] messages, String history,
                         int cursor, long sequence, boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = history;
            this.history = history;
            this.cursor = cursor;
            this.sequence = sequence;
            this.currentSequence = sequence;
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

        public String getHistory() {
            return history;
        }

        public int getCursor() {
            return cursor;
        }

        public long getSequence() {
            return sequence;
        }

        public long getCurrentSequence() {
            return currentSequence;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
