@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    private String[] users = new String[8];
    private int userCount;
    private Message[] messages = new Message[8];
    private int messageCount;
    private String history = "";
    private int cursor;
    private long sequence;
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
        private final String[] users;
        private final Message[] messages;

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
                copy[i] = messages[i];
            }
            return copy;
        }
    }

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
                throw new IllegalArgumentException("Command must not be null");
            }
            int delimiter = command.indexOf(": ");
            if (delimiter < 0) {
                throw new IllegalArgumentException("Malformed command");
            }
            return sendLocked(
                    command.substring(0, delimiter),
                    command.substring(delimiter + 2));
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
                throw new IllegalArgumentException("Position is outside history");
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
        if (sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Sequence numbers exhausted");
        }

        long newLength = (long) history.length()
                + user.length() + text.length() + 3;
        if (newLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("Text history is too large");
        }

        Message[] target = messages;
        if (messageCount == messages.length) {
            target = new Message[grownCapacity(messages.length)];
            for (int i = 0; i < messageCount; i++) {
                target[i] = messages[i];
            }
        }

        long nextSequence = sequence + 1;
        Message message = new Message(nextSequence, user, text);
        StringBuilder builder = new StringBuilder((int) newLength);
        builder.append(history);
        builder.append(user);
        builder.append(": ");
        builder.append(text);
        builder.append('\n');
        String newHistory = builder.toString();

        target[messageCount] = message;
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
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User must not be null or empty");
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

    private static int grownCapacity(int current) {
        if (current == Integer.MAX_VALUE) {
            throw new IllegalStateException("Capacity exhausted");
        }
        long doubled = (long) current * 2;
        return doubled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) doubled;
    }
}
