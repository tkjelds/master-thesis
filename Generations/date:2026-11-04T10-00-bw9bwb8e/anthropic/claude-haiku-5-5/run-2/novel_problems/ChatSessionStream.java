@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}


    /** Immutable structured record of one accepted message. */
    public static final class MessageRecord {
        private final int sequence;
        private final String user;
        private final String text;
        private final int startOffset;
        private final int endOffset;

        MessageRecord(int sequence, String user, String text, int startOffset, int endOffset) {
            this.sequence = sequence;
            this.user = user;
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public int getSequence() {
            return sequence;
        }

        public String getUser() {
            return user;
        }

        public String getText() {
            return text;
        }

        /** Inclusive start index of this record's line in the text history. */
        public int getStartOffset() {
            return startOffset;
        }

        /** Exclusive end index of this record's line in the text history. */
        public int getEndOffset() {
            return endOffset;
        }
    }

    /** Coherent, copied view of the session state at one instant. */
    public static final class Snapshot {
        private final String[] users;
        private final MessageRecord[] records;
        private final String text;
        private final int cursor;
        private final int lastSequence;
        private final boolean closed;

        Snapshot(String[] users, MessageRecord[] records, String text,
                 int cursor, int lastSequence, boolean closed) {
            this.users = users;
            this.records = records;
            this.text = text;
            this.cursor = cursor;
            this.lastSequence = lastSequence;
            this.closed = closed;
        }

        /** Returns a copy of the registered users in insertion order. */
        public String[] getUsers() {
            String[] copy = new String[users.length];
            System.arraycopy(users, 0, copy, 0, users.length);
            return copy;
        }

        /** Returns a copy of all ordered message records. */
        public MessageRecord[] getRecords() {
            MessageRecord[] copy = new MessageRecord[records.length];
            System.arraycopy(records, 0, copy, 0, records.length);
            return copy;
        }

        public String getText() {
            return text;
        }

        public int getCursor() {
            return cursor;
        }

        /** Last assigned sequence number, or 0 if no message has been accepted. */
        public int getLastSequence() {
            return lastSequence;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    // Policy: no operation blocks waiting for state changes. Every operation
    // runs atomically under a single intrinsic monitor. read returns an empty
    // string at the end of history, send/write reject invalid input with
    // exceptions, and a closed session rejects mutations with
    // IllegalStateException while still allowing reads, seeks, and snapshots.

    private final Object lock = new Object();

    private String[] users = new String[4];
    private int userCount;

    private MessageRecord[] records = new MessageRecord[8];
    private int recordCount;

    private final StringBuilder history = new StringBuilder();
    private int cursor;
    private int lastSequence;
    private boolean closed;

    public ChatSessionStream() {
        // Starts with no users, empty history, cursor 0, and an open session.
    }

    public boolean addUser(String user) {
        if (user == null) {
            throw new NullPointerException("user must not be null");
        }
        synchronized (lock) {
            ensureOpenLocked();
            validateUserName(user);
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
            throw new NullPointerException("user must not be null");
        }
        synchronized (lock) {
            ensureOpenLocked();
            int idx = indexOfUserLocked(user);
            if (idx < 0) {
                return false;
            }
            int moved = userCount - idx - 1;
            if (moved > 0) {
                System.arraycopy(users, idx + 1, users, idx, moved);
            }
            users[--userCount] = null;
            return true;
        }
    }

    public int send(String user, String text) {
        if (user == null) {
            throw new NullPointerException("user must not be null");
        }
        if (text == null) {
            throw new NullPointerException("text must not be null");
        }
        synchronized (lock) {
            return appendLocked(user, text);
        }
    }

    public int write(String command) {
        if (command == null) {
            throw new NullPointerException("command must not be null");
        }
        synchronized (lock) {
            ensureOpenLocked();
            int idx = command.indexOf(':');
            if (idx <= 0 || idx + 1 >= command.length() || command.charAt(idx + 1) != ' ') {
                throw new IllegalArgumentException("malformed command");
            }
            String user = command.substring(0, idx);
            String text = command.substring(idx + 2);
            return appendLocked(user, text);
        }
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        synchronized (lock) {
            int available = history.length() - cursor;
            if (available <= 0) {
                return "";
            }
            int n = Math.min(maxChars, available);
            String out = history.substring(cursor, cursor + n);
            cursor += n;
            return out;
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

    // ---- private helpers; all must be called while holding lock ----

    private int appendLocked(String user, String text) {
        ensureOpenLocked();
        if (user.isEmpty() || indexOfUserLocked(user) < 0) {
            throw new IllegalArgumentException("user is not registered: " + user);
        }
        validateText(text);
        if (lastSequence == Integer.MAX_VALUE) {
            throw new IllegalStateException("sequence numbers exhausted");
        }
        int start = history.length();
        long newLength = (long) start + user.length() + 2 + text.length() + 1;
        if (newLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("text history capacity exhausted");
        }
        int seq = lastSequence + 1;
        int end = (int) newLength;

        MessageRecord record = new MessageRecord(seq, user, text, start, end);
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

    private void ensureOpenLocked() {
        if (closed) {
            throw new IllegalStateException("session is closed");
        }
    }

    private int indexOfUserLocked(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].equals(user)) {
                return i;
            }
        }
        return -1;
    }

    private static void validateUserName(String user) {
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
