@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final StringBuilder history = new StringBuilder();
    private int cursor = 0;
    private int sequence = 1;
    private boolean closed = false;

    public synchronized boolean addUser(String user) {
        if (user == null || containsColonCrLf(user)) {
            return false;
        }
        synchronized (lock) {
            if (!history.toString().contains(user + "\n")) {
                history.append(user).append("\n");
                return true;
            }
        }
        return false;
    }

    public synchronized boolean removeUser(String user) {
        if (user == null || containsColonCrLf(user)) {
            return false;
        }
        synchronized (lock) {
            int index = history.indexOf(user + "\n");
            if (index != -1) {
                history.delete(index, index + user.length() + 1);
                return true;
            }
        }
        return false;
    }

    public synchronized int send(String user, String text) {
        if (closed || user == null || containsColonCrLf(user) || text == null) {
            throw new IllegalArgumentException("Invalid send parameters");
        }
        synchronized (lock) {
            history.append(sequence).append(": ").append(user).append(": ").append(text).append("\n");
            return sequence++;
        }
    }

    public synchronized int write(String command) {
        if (closed || command == null) {
            throw new IllegalArgumentException("Invalid write parameters");
        }
        String[] parts = command.split(": ", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed command");
        }
        return send(parts[0], parts[1]);
    }

    public synchronized String read(int maxChars) {
        if (maxChars <= 0 || closed) {
            return "";
        }
        synchronized (lock) {
            int end = cursor + maxChars;
            if (end > history.length()) {
                end = history.length();
            }
            String result = history.substring(cursor, end);
            cursor = end;
            return result;
        }
    }

    public synchronized void seek(int position) {
        if (position < 0 || position > history.length() || closed) {
            return;
        }
        cursor = position;
    }

    public synchronized void close() {
        closed = true;
    }

    public synchronized ChatSessionStream snapshot() {
        return new ChatSessionStream(this);
    }

    private boolean containsColonCrLf(String str) {
        return str.contains(":") || str.contains("\r") || str.contains("\n");
    }

    private ChatSessionStream(ChatSessionStream other) {
        this.history = new StringBuilder(other.history);
        this.cursor = other.cursor;
        this.sequence = other.sequence;
        this.closed = other.closed;
    }
}
