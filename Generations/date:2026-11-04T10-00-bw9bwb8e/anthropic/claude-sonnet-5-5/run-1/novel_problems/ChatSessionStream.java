import java.io.Serializable;

@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
    @interface ThreadSafe {}


    public static final class Message {
        private final long sequence;
        private final String user;
        private final String text;

        Message(long sequence, String user, String text) {
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

        @Override
        public String toString() {
            return sequence + ":" + user + ": " + text;
        }
    }

    public static final class Snapshot {
        private final String[] users;
        private final Message[] messages;
        private final String text;
        private final int cursor;
        private final long sequence;
        private final boolean closed;

        Snapshot(String[] users, Message[] messages, String text, int cursor, long sequence, boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }

        public String[] getUsers() {
            return users.clone();
        }

        public Message[] getMessages() {
            return messages.clone();
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

    private final Object lock = new Object();

    // All guarded by lock
    private String[] users = new String[8];
    private int userCount = 0;
    private Message[] messages = new Message[16];
    private int messageCount = 0;
    private final StringBuilder history = new StringBuilder();
    private int cursor = 0;
    private long lastSequence = 0;
    private boolean closed = false;

    public ChatSessionStream() {
    }

    private static void validateUser(String user) {
        if (user == null) {
            throw new NullPointerException("user");
        }
        if (user.length() == 0) {
            throw new IllegalArgumentException("empty user");
        }
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("invalid user name");
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
                throw new IllegalArgumentException("invalid text");
            }
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

    public boolean addUser(String user) {
        validateUser(user);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session closed");
            }
            if (indexOfUser(user) >= 0) {
                return false;
            }
            if (userCount == users.length) {
                String[] n = new String[users.length * 2];
                System.arraycopy(users, 0, n, 0, userCount);
                users = n;
            }
            users[userCount++] = user;
            return true;
        }
    }

    public boolean removeUser(String user) {
        validateUser(user);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("session closed");
            }
            int idx = indexOfUser(user);
            if (idx < 0) {
                return false;
            }
            for (int i = idx; i < userCount - 1; i++) {
                users[i] = users[i + 1];
            }
            users[--userCount] = null;
            return true;
        }
    }

    public long send(String user, String text) {
        validateUser(user);
        validateText(text);
        synchronized (lock) {
            return sendLocked(user, text);
        }
    }

    // Caller must hold lock
    private long sendLocked(String user, String text) {
        if (closed) {
            throw new IllegalStateException("session closed");
        }
        if (indexOfUser(user) < 0) {
            throw new IllegalArgumentException("user not registered");
        }
        if (lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("sequence exhausted");
        }
        long lineLen = (long) user.length() + 2L + text.length() + 1L;
        if ((long) history.length() + lineLen > (long) (Integer.MAX_VALUE - 16)) {
            throw new IllegalStateException("history capacity exhausted");
        }
        if (messageCount == messages.length) {
            if (messages.length >= Integer.MAX_VALUE / 2) {
                throw new IllegalStateException("history capacity exhausted");
            }
            Message[] n = new Message[messages.length * 2];
            System.arraycopy(messages, 0, n, 0, messageCount);
            messages = n;
        }
        long seq = lastSequence + 1;
        // All checks done; mutate both histories together.
        lastSequence = seq;
        messages[messageCount++] = new Message(seq, user, text);
        history.append(user).append(": ").append(text).append('\n');
        return seq;
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
        validateUser(user);
        validateText(text);
        synchronized (lock) {
            return sendLocked(user, text);
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
            int n = Math.min(available, maxChars);
            String result = history.substring(cursor, cursor + n);
            cursor += n;
            return result;
        }
    }

    public void seek(int position) {
        synchronized (lock) {
            if (position < 0 || position > history.length()) {
                throw new IllegalArgumentException("position out of range");
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
            String[] u = new String[userCount];
            System.arraycopy(users, 0, u, 0, userCount);
            Message[] m = new Message[messageCount];
            System.arraycopy(messages, 0, m, 0, messageCount);
            return new Snapshot(u, m, history.toString(), cursor, lastSequence, closed);
        }
    }
}
