@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private boolean closed = false;
    private volatile int sequence = 0;
    private final StringBuilder history = new StringBuilder();
    private String[] users;

    public boolean addUser(String user) {
        synchronized (lock) {
            if (closed) return false;
            if (users == null) users = new String[0];
            for (String u : users) {
                if (u.equals(user)) return false;
            }
            String[] newUsers = new String[users.length + 1];
            System.arraycopy(users, 0, newUsers, 0, users.length);
            newUsers[newUsers.length - 1] = user;
            users = newUsers;
            return true;
        }
    }

    public boolean removeUser(String user) {
        synchronized (lock) {
            if (closed) return false;
            int index = -1;
            for (int i = 0; i < users.length; i++) {
                if (users[i].equals(user)) {
                    index = i;
                    break;
                }
            }
            if (index == -1) return false;
            String[] newUsers = new String[users.length - 1];
            System.arraycopy(users, 0, newUsers, 0, index);
            System.arraycopy(users, index + 1, newUsers, index, users.length - index - 1);
            users = newUsers;
            return true;
        }
    }

    public int send(String user, String text) {
        synchronized (lock) {
            if (closed || user == null || text == null) throw new IllegalArgumentException();
            for (String u : users) {
                if (u.equals(user)) {
                    synchronized (history) {
                        history.append(sequence).append(": ").append(user).append(": ").append(text).append("\n");
                    }
                    return sequence++;
                }
            }
            throw new IllegalArgumentException();
        }
    }

    public int write(String command) {
        synchronized (lock) {
            if (closed || command == null) throw new IllegalArgumentException();
            int i = command.indexOf(": ");
            if (i == -1) throw new IllegalArgumentException();
            String user = command.substring(0, i);
            String text = command.substring(i + 2);
            return send(user, text);
        }
    }

    public String read(int maxChars) {
        synchronized (lock) {
            if (maxChars <= 0) throw new IllegalArgumentException();
            historyLock:
            while (true) {
                StringBuilder result = new StringBuilder();
                synchronized (history) {
                    if (result.length() >= maxChars) break historyLock;
                    int length = history.length();
                    if (length > cursor && length - cursor <= maxChars) {
                        result.append(history.substring(cursor, length));
                        cursor = length;
                        break historyLock;
                    }
                }
            }
            return result.toString();
        }
    }

    public void seek(int position) {
        synchronized (lock) {
            if (position < 0 || position > cursor) throw new IllegalArgumentException();
            cursor = position;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException();
            return new Object[] {
                users == null ? null : users.clone(),
                history.toString().split("\n"),
                history.length() - 1,
                sequence
            };
        }
    }

    private int cursor = 0;
}
