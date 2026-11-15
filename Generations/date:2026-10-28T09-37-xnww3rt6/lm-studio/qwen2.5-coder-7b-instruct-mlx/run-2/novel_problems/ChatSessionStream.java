@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
    @interface ThreadSafe {}

    private final String[] users;
    private final String[][] history;
    private int cursor;
    private int sequence;
    private boolean closed;

    public ChatSessionStream() {
        users = new String[1024];
        history = new String[1024][];
        cursor = 0;
        sequence = 0;
        closed = false;
    }

    public boolean addUser(String user) {
        synchronized (this) {
            if (closed) {
                return false;
            }
            for (String u : users) {
                if (u != null && u.equals(user)) {
                    return false;
                }
            }
            for (int i = 0; i < users.length; i++) {
                if (users[i] == null) {
                    users[i] = user;
                    return true;
                }
            }
            return false; // Not enough space for new user
        }
    }

    public boolean removeUser(String user) {
        synchronized (this) {
            if (closed) {
                return false;
            }
            for (int i = 0; i < users.length; i++) {
                if (users[i] != null && users[i].equals(user)) {
                    users[i] = null;
                    return true;
                }
            }
            return false; // User not found
        }
    }

    public int send(String user, String text) {
        synchronized (this) {
            if (closed || !isUserRegistered(user)) {
                throw new IllegalArgumentException("User not registered");
            }
            history[sequence] = new String[]{String.valueOf(sequence), user + ": " + text + "\n"};
            return sequence++;
        }
    }

    public int write(String command) {
        String[] parts = command.split(": ", 2);
        if (parts.length != 2 || parts[0] == null || parts[1] == null) {
            return -1; // Malformed command
        }
        return send(parts[0], parts[1]);
    }

    public String read(int maxChars) {
        synchronized (this) {
            if (maxChars <= 0) {
                return "";
            }
            StringBuilder result = new StringBuilder();
            while (cursor < history.length && result.length() + history[cursor].length() <= maxChars) {
                result.append(history[cursor][1]);
                cursor++;
            }
            return result.toString();
        }
    }

    public void seek(int position) {
        synchronized (this) {
            if (position < 0 || position > history.length) {
                throw new IllegalArgumentException("Invalid seek position");
            }
            cursor = position;
        }
    }

    public void close() {
        synchronized (this) {
            closed = true;
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            String[] usersCopy = new String[users.length];
            System.arraycopy(users, 0, usersCopy, 0, usersCopy.length);
            String[][] historyCopy = new String[history.length][];
            for (int i = 0; i < historyCopy.length; i++) {
                if (history[i] != null) {
                    historyCopy[i] = new String[2];
                    System.arraycopy(history[i], 0, historyCopy[i], 0, historyCopy[i].length);
                }
            }
            return new Object[]{usersCopy, historyCopy, getAllText(), cursor, sequence, closed};
        }
    }

    private boolean isUserRegistered(String user) {
        for (String u : users) {
            if (u != null && u.equals(user)) {
                return true;
            }
        }
        return false;
    }

    private String getAllText() {
        StringBuilder result = new StringBuilder();
        for (String[] record : history) {
            if (record != null) {
                result.append(record[1]);
            }
        }
        return result.toString();
    }
}
