import java.lang.*;

@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
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
        private final long lastSequence;
        private final boolean closed;

        private Snapshot(String[] users, Message[] messages, String text, int cursor,
                         long lastSequence, boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.lastSequence = lastSequence;
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

        public long getLastSequence() {
            return lastSequence;
        }

        public long getCurrentSequence() {
            return lastSequence;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private final Object lock = new Object();

    // All fields below guarded by lock.
    private String[] users = new String[8];
    private int userCount = 0;
    private Message[] records = new Message[16];
    private int recordCount = 0;
    private char[] buffer = new char[256];
    private int length = 0;
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
                throw new IllegalArgumentException("invalid user");
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
                throw new IllegalStateException("closed");
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
                throw new IllegalStateException("closed");
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

    // Must be called holding lock.
    private long sendLocked(String user, String text) {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        if (indexOfUser(user) < 0) {
            throw new IllegalArgumentException("unknown user");
        }
        if (lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("sequence exhausted");
        }
        long add = (long) user.length() + 2L + (long) text.length() + 1L;
        long newLen = (long) length + add;
        if (newLen > Integer.MAX_VALUE - 16) {
            throw new IllegalStateException("history too large");
        }
        int need = (int) newLen;
        // Allocate everything before mutating, so failures leave state unchanged.
        char[] nb = buffer;
        if (need > buffer.length) {
            long cap = (long) buffer.length * 2L;
            if (cap < need) {
                cap = need;
            }
            if (cap > Integer.MAX_VALUE - 16) {
                cap = Integer.MAX_VALUE - 16;
            }
            nb = new char[(int) cap];
            System.arraycopy(buffer, 0, nb, 0, length);
        }
        Message[] nr = records;
        if (recordCount == records.length) {
            nr = new Message[records.length * 2];
            System.arraycopy(records, 0, nr, 0, recordCount);
        }
        long seq = lastSequence + 1;
        Message m = new Message(seq, user, text);
        // Commit.
        buffer = nb;
        records = nr;
        int p = length;
        for (int i = 0; i < user.length(); i++) {
            buffer[p++] = user.charAt(i);
        }
        buffer[p++] = ':';
        buffer[p++] = ' ';
        for (int i = 0; i < text.length(); i++) {
            buffer[p++] = text.charAt(i);
        }
        buffer[p++] = '\n';
        length = p;
        records[recordCount++] = m;
        lastSequence = seq;
        return seq;
    }

    public long write(String command) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        int colon = command.indexOf(':');
        if (colon <= 0 || colon + 1 >= command.length() || command.charAt(colon + 1) != ' ') {
            throw new IllegalArgumentException("malformed command");
        }
        String user = command.substring(0, colon);
        String text = command.substring(colon + 2);
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
            int avail = length - cursor;
            if (avail <= 0) {
                return "";
            }
            int n = avail < maxChars ? avail : maxChars;
            String s = new String(buffer, cursor, n);
            cursor += n;
            return s;
        }
    }

    public void seek(int position) {
        synchronized (lock) {
            if (position < 0 || position > length) {
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
            Message[] m = new Message[recordCount];
            System.arraycopy(records, 0, m, 0, recordCount);
            return new Snapshot(u, m, new String(buffer, 0, length), cursor, lastSequence, closed);
        }
    }
}
