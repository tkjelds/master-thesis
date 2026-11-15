@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private final String[] users;
    private final String[] history;
    private int cursor;
    private long sequence;
    private boolean closed;

    public ChatSessionStream() {
        users = new String[10];
        history = new String[100];
    }

    public synchronized boolean addUser(String user) {
        if (user == null || closed) return false;
        for (String u : users) {
            if (u != null && u.equals(user)) return false;
        }
        for (int i = 0; i < users.length; i++) {
            if (users[i] == null) {
                users[i] = user;
                return true;
            }
        }
        return false;
    }

    public synchronized boolean removeUser(String user) {
        if (user == null || closed) return false;
        for (int i = 0; i < users.length; i++) {
            if (users[i] != null && users[i].equals(user)) {
                users[i] = null;
                return true;
            }
        }
        return false;
    }

    public synchronized long send(String user, String text) {
        if (user == null || closed) throw new IllegalStateException();
        for (String u : users) {
            if (u != null && u.equals(user)) {
                history[(int) sequence % history.length] = user + ": " + text + "\n";
                sequence++;
                return sequence - 1;
            }
        }
        throw new IllegalArgumentException("User not found");
    }

    public synchronized long write(String command) {
        if (command == null || closed) throw new IllegalStateException();
        int colonIndex = command.indexOf(": ");
        if (colonIndex < 0) return -1;
        String user = command.substring(0, colonIndex);
        String text = command.substring(colonIndex + 2);
        return send(user, text);
    }

    public synchronized String read(int maxChars) {
        if (maxChars <= 0 || closed) return "";
        StringBuilder sb = new StringBuilder();
        int start = cursor % history.length;
        int length = 0;
        for (int i = start; i < start + history.length && length < maxChars; i++) {
            String record = history[i % history.length];
            if (record != null) {
                sb.append(record);
                length += record.length();
            }
        }
        cursor = (cursor + length) % history.length;
        return sb.toString();
    }

    public synchronized void seek(int position) {
        if (position < 0 || closed) throw new IllegalStateException();
        cursor = (cursor + position * history.length) % history.length;
    }

    public synchronized void close() {
        closed = true;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(users.clone(), history.clone(), cursor, sequence, closed);
    }

    public static final class Snapshot {
        private final String[] users;
        private final String[] history;
        private final int cursor;
        private final long sequence;
        private final boolean closed;

        public Snapshot(String[] users, String[] history, int cursor, long sequence, boolean closed) {
            this.users = users;
            this.history = history;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }

        public String[] getUsers() {
            return users;
        }

        public String[] getHistory() {
            return history;
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
