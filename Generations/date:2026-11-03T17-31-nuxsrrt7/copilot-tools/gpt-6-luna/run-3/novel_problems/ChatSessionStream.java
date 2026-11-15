@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private String[] users = new String[4];
    private int userCount;
    private Message[] messages = new Message[4];
    private int messageCount;
    private char[] history = new char[16];
    private int historyLength;
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
            ensureUserCapacity(userCount + 1);
            users[userCount++] = user;
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
            int remaining = userCount - index - 1;
            if (remaining > 0) {
                for (int i = index; i < userCount - 1; i++) {
                    users[i] = users[i + 1];
                }
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
                throw new IllegalArgumentException("User is not registered: " + user);
            }
            if (sequence == Long.MAX_VALUE) {
                throw new IllegalStateException("Message sequence exhausted");
            }

            String line = user + ": " + text + "\n";
            if (line.length() > Integer.MAX_VALUE - historyLength) {
                throw new IllegalStateException("Message history is too large");
            }
            ensureMessageCapacity(messageCount + 1);
            ensureHistoryCapacity(historyLength + line.length());

            long nextSequence = sequence + 1;
            Message message = new Message(nextSequence, user, text);
            line.getChars(0, line.length(), history, historyLength);
            messages[messageCount] = message;
            historyLength += line.length();
            messageCount++;
            sequence = nextSequence;
            return nextSequence;
        }
    }

    public long write(String command) {
        if (command == null) {
            throw new IllegalArgumentException("Command must not be null");
        }
        int delimiter = command.indexOf(": ");
        if (delimiter <= 0) {
            throw new IllegalArgumentException("Command must have the form user + \": \" + text");
        }
        String user = command.substring(0, delimiter);
        String text = command.substring(delimiter + 2);
        validateUser(user);
        validateText(text);
        return send(user, text);
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        synchronized (lock) {
            int count = Math.min(maxChars, historyLength - cursor);
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
            Message[] messageCopy = new Message[messageCount];
            for (int i = 0; i < messageCount; i++) {
                messageCopy[i] = messages[i];
            }
            return new Snapshot(
                    userCopy,
                    messageCopy,
                    new String(history, 0, historyLength),
                    cursor,
                    sequence,
                    closed);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Chat session is closed");
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

    private void ensureUserCapacity(int required) {
        if (required <= users.length) {
            return;
        }
        int capacity = grownCapacity(users.length, required);
        String[] expanded = new String[capacity];
        for (int i = 0; i < userCount; i++) {
            expanded[i] = users[i];
        }
        users = expanded;
    }

    private void ensureMessageCapacity(int required) {
        if (required <= messages.length) {
            return;
        }
        int capacity = grownCapacity(messages.length, required);
        Message[] expanded = new Message[capacity];
        for (int i = 0; i < messageCount; i++) {
            expanded[i] = messages[i];
        }
        messages = expanded;
    }

    private void ensureHistoryCapacity(int required) {
        if (required <= history.length) {
            return;
        }
        int capacity = grownCapacity(history.length, required);
        char[] expanded = new char[capacity];
        for (int i = 0; i < historyLength; i++) {
            expanded[i] = history[i];
        }
        history = expanded;
    }

    private static int grownCapacity(int current, int required) {
        int capacity = current;
        while (capacity < required) {
            int next = capacity <= Integer.MAX_VALUE / 2
                    ? capacity * 2
                    : Integer.MAX_VALUE;
            if (next <= capacity) {
                throw new IllegalStateException("Capacity exhausted");
            }
            capacity = next;
        }
        return capacity;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User must be nonempty");
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("User contains a forbidden character");
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
                throw new IllegalArgumentException("Text contains a line break");
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

        public long sequence() {
            return sequence;
        }

        public String user() {
            return user;
        }

        public String text() {
            return text;
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
                boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }

        public String[] users() {
            String[] copy = new String[users.length];
            for (int i = 0; i < users.length; i++) {
                copy[i] = users[i];
            }
            return copy;
        }

        public Message[] messages() {
            Message[] copy = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                copy[i] = messages[i];
            }
            return copy;
        }

        public String text() {
            return text;
        }

        public int cursor() {
            return cursor;
        }

        public long sequence() {
            return sequence;
        }

        public boolean closed() {
            return closed;
        }
    }
}
