@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private String[] users = new String[0];
    private int userCount;

    private MessageRecord[] messages = new MessageRecord[0];
    private int messageCount;

    private String history = "";
    private int cursor;
    private long sequence;
    private boolean closed;

    public boolean addUser(String user) {
        validateUser(user);

        synchronized (this) {
            ensureOpen();

            if (findUser(user) >= 0) {
                return false;
            }

            String[] target = users;
            if (userCount == users.length) {
                target = growUsers(users, userCount + 1);
            }

            target[userCount] = user;
            users = target;
            userCount++;
            return true;
        }
    }

    public boolean removeUser(String user) {
        validateUser(user);

        synchronized (this) {
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

        String line = user + ": " + text + "\n";

        synchronized (this) {
            ensureOpen();

            if (findUser(user) < 0) {
                throw new IllegalArgumentException("User is not registered");
            }
            if (sequence == Long.MAX_VALUE) {
                throw new IllegalStateException("Sequence numbers are exhausted");
            }

            long nextSequence = sequence + 1;
            String newHistory = history + line;
            MessageRecord record = new MessageRecord(nextSequence, user, text);

            MessageRecord[] target = messages;
            if (messageCount == messages.length) {
                target = growMessages(messages, messageCount + 1);
            }

            target[messageCount] = record;
            messages = target;
            messageCount++;
            history = newHistory;
            sequence = nextSequence;
            return nextSequence;
        }
    }

    public long write(String command) {
        if (command == null) {
            throw new IllegalArgumentException("Command must not be null");
        }

        int colon = command.indexOf(':');
        if (colon <= 0 || colon + 1 >= command.length()
                || command.charAt(colon + 1) != ' ') {
            throw new IllegalArgumentException("Malformed command");
        }

        String user = command.substring(0, colon);
        String text = command.substring(colon + 2);
        validateUser(user);
        validateText(text);
        return send(user, text);
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }

        synchronized (this) {
            int count = Math.min(maxChars, history.length() - cursor);
            int end = cursor + count;
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
            String[] copiedUsers = new String[userCount];
            System.arraycopy(users, 0, copiedUsers, 0, userCount);

            MessageRecord[] copiedMessages = new MessageRecord[messageCount];
            for (int i = 0; i < messageCount; i++) {
                MessageRecord record = messages[i];
                copiedMessages[i] =
                        new MessageRecord(record.sequence, record.user, record.text);
            }

            return new Snapshot(
                    copiedUsers, copiedMessages, history, cursor, sequence, closed);
        }
    }

    private void ensureOpen() {
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
            throw new IllegalArgumentException("User must not be null");
        }
        if (user.length() == 0) {
            throw new IllegalArgumentException("User must not be empty");
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("Invalid character in user");
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
                throw new IllegalArgumentException("Text must not contain CR or LF");
            }
        }
    }

    private static String[] growUsers(String[] source, int required) {
        int capacity = source.length == 0 ? 1 : source.length;
        while (capacity < required) {
            int next = capacity <= (Integer.MAX_VALUE - 1) / 2
                    ? capacity * 2
                    : Integer.MAX_VALUE;
            if (next <= capacity) {
                throw new OutOfMemoryError("Too many users");
            }
            capacity = next;
        }

        String[] result = new String[capacity];
        System.arraycopy(source, 0, result, 0, source.length);
        return result;
    }

    private static MessageRecord[] growMessages(
            MessageRecord[] source, int required) {
        int capacity = source.length == 0 ? 1 : source.length;
        while (capacity < required) {
            int next = capacity <= (Integer.MAX_VALUE - 1) / 2
                    ? capacity * 2
                    : Integer.MAX_VALUE;
            if (next <= capacity) {
                throw new OutOfMemoryError("Too many messages");
            }
            capacity = next;
        }

        MessageRecord[] result = new MessageRecord[capacity];
        System.arraycopy(source, 0, result, 0, source.length);
        return result;
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
    }

    public static final class Snapshot {
        private final String[] users;
        private final MessageRecord[] messages;

        public final String completeText;
        public final int cursor;
        public final long sequence;
        public final long currentSequence;
        public final boolean closed;

        private Snapshot(
                String[] users,
                MessageRecord[] messages,
                String completeText,
                int cursor,
                long sequence,
                boolean closed) {
            this.users = users;
            this.messages = messages;
            this.completeText = completeText;
            this.cursor = cursor;
            this.sequence = sequence;
            this.currentSequence = sequence;
            this.closed = closed;
        }

        public String[] getUsers() {
            String[] copy = new String[users.length];
            System.arraycopy(users, 0, copy, 0, users.length);
            return copy;
        }

        public MessageRecord[] getMessages() {
            MessageRecord[] copy = new MessageRecord[messages.length];
            for (int i = 0; i < messages.length; i++) {
                MessageRecord record = messages[i];
                copy[i] = new MessageRecord(
                        record.sequence, record.user, record.text);
            }
            return copy;
        }

        public String getText() {
            return completeText;
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
