@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}


    public static final class MessageRecord {
        private final long sequence;
        private final String user;
        private final String text;

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
        private final String[] users;
        private final MessageRecord[] messages;
        private final String text;
        private final int cursor;
        private final long lastSequence;
        private final boolean closed;

        private Snapshot(String[] users, MessageRecord[] messages, String text,
                         int cursor, long lastSequence, boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.lastSequence = lastSequence;
            this.closed = closed;
        }

        public String[] getUsers() {
            String[] copy = new String[users.length];
            System.arraycopy(users, 0, copy, 0, users.length);
            return copy;
        }

        public MessageRecord[] getMessages() {
            MessageRecord[] copy = new MessageRecord[messages.length];
            System.arraycopy(messages, 0, copy, 0, messages.length);
            return copy;
        }

        public String getText() {
            return text;
        }

        public int getCursor() {
            return cursor;
        }

        public long getLastSequence() {
            return lastSequence;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    // All mutable state is guarded by 'lock'. Operations never block waiting
    // for other sessions state: read() returns an empty string at the end,
    // and write/send/membership changes either complete immediately or throw.
    private final Object lock = new Object();
    private String[] users = new String[4];
    private int userCount;
    private MessageRecord[] records = new MessageRecord[16];
    private int recordCount;
    private final StringBuilder history = new StringBuilder();
    private int cursor;
    private long lastSequence; // 0 means no message accepted yet
    private boolean closed;

    public ChatSessionStream() {
        // Starts with no users, empty history, cursor 0, open session.
    }

    public boolean addUser(String user) {
        if (user == null) {
            throw new NullPointerException("user");
        }
        validateUser(user);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session is closed");
            }
            if (indexOfUserLocked(user) >= 0) {
                return false;
            }
            if (userCount == users.length) {
                String[] grown = new String[users.length * 2];
                System.arraycopy(users, 0, grown, 0, userCount);
                users = grown;
            }
            users[userCount++] = user;
            return true;
        }
    }

    public boolean removeUser(String user) {
        if (user == null) {
            throw new NullPointerException("user");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session is closed");
            }
            int idx = indexOfUserLocked(user);
            if (idx < 0) {
                return false;
            }
            // Preserve insertion order of remaining users.
            System.arraycopy(users, idx + 1, users, idx, userCount - idx - 1);
            userCount--;
            users[userCount] = null;
            return true;
        }
    }

    public long send(String user, String text) {
        if (user == null) {
            throw new NullPointerException("user");
        }
        if (text == null) {
            throw new NullPointerException("text");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session is closed");
            }
            validateUser(user);
            validateText(text);
            if (indexOfUserLocked(user) < 0) {
                throw new IllegalArgumentException("user is not registered: " + user);
            }
            return appendLocked(user, text);
        }
    }

    public long write(String command) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        int idx = command.indexOf(": ");
        if (idx < 0) {
            throw new IllegalArgumentException("malformed command");
        }
        String user = command.substring(0, idx);
        String text = command.substring(idx + 2);
        // send performs all validation and the append atomically.
        return send(user, text);
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        synchronized (lock) {
            int available = history.length() - cursor;
            int n = Math.min(maxChars, available);
            if (n <= 0) {
                return "";
            }
            String result = history.substring(cursor, cursor + n);
            cursor += n;
            return result;
        }
    }

    public void seek(int position) {
        synchronized (lock) {
            if (position < 0 || position > history.length()) {
                throw new IllegalArgumentException("position out of range: " + position);
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
            System.arraycopy(users, 0, userCopy, 0, userCount);
            MessageRecord[] recordCopy = new MessageRecord[recordCount];
            System.arraycopy(records, 0, recordCopy, 0, recordCount);
            return new Snapshot(userCopy, recordCopy, history.toString(),
                    cursor, lastSequence, closed);
        }
    }

    // Must be called while holding lock; inputs already validated.
    private long appendLocked(String user, String text) {
        if (lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("sequence numbers exhausted");
        }
        long entryLength = (long) user.length() + 2L + text.length() + 1L;
        if ((long) history.length() + entryLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("text history capacity exhausted");
        }
        long seq = lastSequence + 1;
        MessageRecord record = new MessageRecord(seq, user, text);
        if (recordCount == records.length) {
            MessageRecord[] grown = new MessageRecord[records.length * 2];
            System.arraycopy(records, 0, grown, 0, recordCount);
            records = grown;
        }
        records[recordCount++] = record;
        history.append(user).append(": ").append(text).append('\n');
        lastSequence = seq;
        return seq;
    }

    // Must be called while holding lock.
    private int indexOfUserLocked(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].equals(user)) {
                return i;
            }
        }
        return -1;
    }

    private static void validateUser(String user) {
        if (user.isEmpty()) {
            throw new IllegalArgumentException("user name must be nonempty");
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("user name contains forbidden character");
            }
        }
    }

    private static void validateText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("message text contains CR or LF");
            }
        }
    }
}
