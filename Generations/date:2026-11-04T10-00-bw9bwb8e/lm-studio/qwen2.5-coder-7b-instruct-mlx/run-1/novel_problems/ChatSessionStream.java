import java.util.*;

@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
    @interface ThreadSafe {}

    private final List<String> users = Collections.synchronizedList(new ArrayList<>());
    private final List<String> history = new ArrayList<>();
    private int cursor = 0;
    private volatile boolean closed = false;

    public boolean addUser(String user) {
        if (user == null || user.isEmpty() || user.contains(":") || user.contains("\r") || user.contains("\n")) {
            return false;
        }
        synchronized (users) {
            boolean added = users.add(user);
            if (added) {
                notifyAll();
            }
            return added;
        }
    }

    public boolean removeUser(String user) {
        if (user == null || user.isEmpty() || user.contains(":") || user.contains("\r") || user.contains("\n")) {
            return false;
        }
        synchronized (users) {
            boolean removed = users.remove(user);
            if (removed) {
                notifyAll();
            }
            return removed;
        }
    }

    public int send(String user, String text) {
        if (user == null || text == null) {
            throw new IllegalArgumentException();
        }
        synchronized (this) {
            if (closed || !users.contains(user)) {
                throw new IllegalStateException();
            }
            int sequence = history.size() + 1;
            history.add(sequence + ": " + user + ": " + text + "\n");
            notifyAll();
            return sequence;
        }
    }

    public int write(String command) {
        if (command == null || !command.contains(": ")) {
            return -1;
        }
        String[] parts = command.split(": ", 2);
        if (parts.length != 2) {
            return -1;
        }
        String user = parts[0];
        String text = parts[1];
        return send(user, text);
    }

    public String read(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException();
        }
        synchronized (this) {
            while (closed || cursor >= history.size()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "";
                }
            }
            int end = Math.min(cursor + maxChars, history.size());
            StringBuilder result = new StringBuilder();
            for (int i = cursor; i < end; i++) {
                result.append(history.get(i));
            }
            cursor = end;
            return result.toString();
        }
    }

    public void seek(int position) {
        if (position < 0 || position > history.size()) {
            throw new IllegalArgumentException();
        }
        synchronized (this) {
            cursor = position;
            notifyAll();
        }
    }

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(new ArrayList<>(users), new ArrayList<>(history), cursor, history.size(), closed);
        }
    }

    public static class Snapshot {
        private final List<String> users;
        private final List<String> history;
        private final int cursor;
        private final int sequence;
        private final boolean closed;

        public Snapshot(List<String> users, List<String> history, int cursor, int sequence, boolean closed) {
            this.users = users;
            this.history = history;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }

        public List<String> getUsers() {
            return Collections.unmodifiableList(users);
        }

        public List<String> getHistory() {
            return Collections.unmodifiableList(history);
        }

        public int getCursor() {
            return cursor;
        }

        public int getSequence() {
            return sequence;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
