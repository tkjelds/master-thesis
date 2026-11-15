@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}


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
        private final long lastSequence;
        private final boolean closed;

        private Snapshot(String[] users, Message[] messages, String text,
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

        public Message[] getMessages() {
            Message[] copy = new Message[messages.length];
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

    // Policy: no operation blocks on a condition. Every public operation runs to
    // completion (or throws) while holding the single intrinsic lock. Invalid
    // arguments throw IllegalArgumentException, null arguments throw
    // NullPointerException, operations on a closed session throw
    // IllegalStateException, and exhausted sequence/text space throws
    // IllegalStateException. Rejected operations leave all state unchanged.

    private final Object lock = new Object();

    private String[] users = new String[4];
    private int userCount;

    private Message[] messages = new Message[16];
    private int messageCount;

    private char[] text = new char[64];
    private int textLength;

    private int cursor;
    private long nextSequence = 1L;
    private boolean closed;

    public ChatSessionStream() {
    }

    private static void checkUser(String user) {
        if (user == null) {
            throw new NullPointerException("user");
        }
        if (user.isEmpty()) {
            throw new IllegalArgumentException("user must be nonempty");
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("user contains forbidden character");
            }
        }
    }

    private static void checkText(String msg) {
        if (msg == null) {
            throw new NullPointerException("text");
        }
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("text contains CR or LF");
            }
        }
    }

    // Must be called with lock held.
    private int indexOfUserLocked(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].equals(user)) {
                return i;
            }
        }
        return -1;
    }

    public boolean addUser(String user) {
        checkUser(user);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session closed");
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
        checkUser(user);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session closed");
            }
            int idx = indexOfUserLocked(user);
            if (idx < 0) {
                return false;
            }
            for (int i = idx; i < userCount - 1; i++) {
                users[i] = users[i + 1];
            }
            userCount--;
            users[userCount] = null;
            return true;
        }
    }

    // Must be called with lock held; assumes validation of arguments already done.
    private long appendLocked(String user, String msg) {
        if (closed) {
            throw new IllegalStateException("session closed");
        }
        if (indexOfUserLocked(user) < 0) {
            throw new IllegalArgumentException("user not registered: " + user);
        }
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("sequence numbers exhausted");
        }
        String line = user + ": " + msg + "\n";
        long newLength = (long) textLength + (long) line.length();
        if (newLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("text history exhausted");
        }

        // Ensure capacity (no logical state changed yet beyond growth).
        if (newLength > text.length) {
            long cap = Math.max(64L, (long) text.length);
            while (cap < newLength) {
                cap = cap * 2L;
                if (cap > Integer.MAX_VALUE - 8) {
                    cap = Integer.MAX_VALUE - 8;
                    break;
                }
            }
            char[] grown = new char[(int) cap];
            System.arraycopy(text, 0, grown, 0, textLength);
            text = grown;
        }
        if (messageCount == messages.length) {
            Message[] grown = new Message[messages.length * 2];
            System.arraycopy(messages, 0, grown, 0, messageCount);
            messages = grown;
        }

        long seq = nextSequence;
        nextSequence++;
        messages[messageCount++] = new Message(seq, user, msg);
        line.getChars(0, line.length(), text, textLength);
        textLength += line.length();
        return seq;
    }

    public long send(String user, String msg) {
        checkUser(user);
        checkText(msg);
        synchronized (lock) {
            return appendLocked(user, msg);
        }
    }

    public long write(String command) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        int delim = command.indexOf(": ");
        if (delim < 0) {
            throw new IllegalArgumentException("malformed command");
        }
        String user = command.substring(0, delim);
        String msg = command.substring(delim + 2);
        checkUser(user);
        checkText(msg);
        synchronized (lock) {
            return appendLocked(user, msg);
        }
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        synchronized (lock) {
            int available = textLength - cursor;
            int n = Math.min(maxChars, available);
            if (n <= 0) {
                return "";
            }
            String result = new String(text, cursor, n);
            cursor += n;
            return result;
        }
    }

    public void seek(int position) {
        synchronized (lock) {
            if (position < 0 || position > textLength) {
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
            Message[] msgCopy = new Message[messageCount];
            System.arraycopy(messages, 0, msgCopy, 0, messageCount);
            String textCopy = new String(text, 0, textLength);
            return new Snapshot(userCopy, msgCopy, textCopy, cursor,
                    nextSequence - 1L, closed);
        }
    }
}
