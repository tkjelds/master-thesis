import java.util.*;
import java.io.*;

@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
    @interface ThreadSafe {}

    private List<String> users = new ArrayList<>();
    private List<String> history = new ArrayList<>();
    private int cursor = 0;
    private volatile boolean closed = false;
    private int sequence = 1;

    public synchronized boolean addUser(String user) {
        if (closed || user == null || user.isEmpty() || user.contains(":") || user.contains("\r") || user.contains("\n")) {
            return false;
        }
        if (users.add(user)) {
            notifyAll();
            return true;
        }
        return false;
    }

    public synchronized boolean removeUser(String user) {
        if (closed || user == null || user.isEmpty() || user.contains(":") || user.contains("\r") || user.contains("\n")) {
            return false;
        }
        if (users.remove(user)) {
            notifyAll();
            return true;
        }
        return false;
    }

    public synchronized int send(String user, String text) {
        if (closed || user == null || user.isEmpty() || user.contains(":") || user.contains("\r") || user.contains("\n")) {
            throw new IllegalArgumentException("Invalid or closed session");
        }
        if (!users.contains(user)) {
            throw new IllegalArgumentException("User not registered");
        }
        String record = sequence + ": " + user + ": " + text + "\n";
        history.add(record);
        notifyAll();
        return sequence++;
    }

    public synchronized void write(String command) {
        if (closed || command == null || command.isEmpty() || command.contains("\r") || command.contains("\n")) {
            return;
        }
        String[] parts = command.split(": ", 2);
        if (parts.length != 2) {
            return;
        }
        String user = parts[0];
        String text = parts[1];
        send(user, text);
    }

    public synchronized String read(int maxChars) {
        if (closed || maxChars <= 0) {
            return "";
        }
        int start = cursor;
        int end = Math.min(start + maxChars, history.size());
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            result.append(history.get(i));
        }
        cursor = end;
        return result.toString();
    }

    public synchronized void seek(int position) {
        if (closed || position < 0 || position > history.size()) {
            return;
        }
        cursor = position;
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized ChatSessionStream snapshot() {
        return new ChatSessionStream(users, history, cursor, sequence, closed);
    }

    private ChatSessionStream(List<String> users, List<String> history, int cursor, int sequence, boolean closed) {
        this.users = new ArrayList<>(users);
        this.history = new ArrayList<>(history);
        this.cursor = cursor;
        this.sequence = sequence;
        this.closed = closed;
    }

    private List<String> cloneList(List<String> list) {
        return new ArrayList<>(list);
    }
}
