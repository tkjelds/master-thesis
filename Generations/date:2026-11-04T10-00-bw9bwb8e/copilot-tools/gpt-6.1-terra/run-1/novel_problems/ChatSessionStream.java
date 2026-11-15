@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private static final long MAX_SEQUENCE = 9223372036854775807L;
    private static final int MAX_ARRAY_LENGTH = 2147483647;

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
        synchronized (lock) {
            requireOpen();
            validateUser(user);

            if (findUser(user) >= 0) {
                return false;
            }

            if (userCount == MAX_ARRAY_LENGTH) {
                throw new IllegalStateException("User capacity exhausted");
            }

            if (userCount == users.length) {
                String[] expanded = new String[grownCapacity(users.length)];
                for (int i = 0; i < userCount; i++) {
                    expanded[i] = users[i];
                }
                users = expanded;
            }

            users[userCount] = user;
            userCount++;
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
            userCount--;
            users[userCount] = null;
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
                userCopy, messageCopy, history, cursor, sequence, closed
            );
        }
    }

    private long sendLocked(String user, String text) {
        validateUser(user);
        validateText(text);

        if (findUser(user) < 0) {
            throw new IllegalArgumentException("User is not registered");
        }
        if (sequence == MAX_SEQUENCE) {
            throw new IllegalStateException("Sequence numbers exhausted");
        }
        if (messageCount == MAX_ARRAY_LENGTH) {
            throw new IllegalStateException("Message capacity exhausted");
        }

        long newLength = (long) history.length()
            + user.length() + 2L + text.length() + 1L;
        if (newLength > MAX_ARRAY_LENGTH) {
            throw new IllegalStateException("Text history capacity exhausted");
        }

        long newSequence = sequence + 1;
        Message message = new Message(newSequence, user, text);
        String newHistory = history + user + ": " + text + "\n";

        Message[] target = messages;
        if (messageCount == messages.length) {
            target = new Message[grownCapacity(messages.length)];
            for (int i = 0; i < messageCount; i++) {
                target[i] = messages[i];
            }
        }

        target[messageCount] = message;
        messages = target;
        messageCount++;
        history = newHistory;
        sequence = newSequence;
        return newSequence;
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

    private static int grownCapacity(int current) {
        long next = current == 0 ? 1L : (long) current * 2L;
        return next > MAX_ARRAY_LENGTH ? MAX_ARRAY_LENGTH : (int) next;
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
