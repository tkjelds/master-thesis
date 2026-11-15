@ChatSessionStream.ThreadSafe
public final class ChatSessionStream {
    @interface ThreadSafe {}

    private static final long MAX_SEQUENCE = 9223372036854775807L;

    private static final class Monitor {
    }

    private final Monitor monitor = new Monitor();
    private String[] users = new String[0];
    private Message[] messages = new Message[0];
    private String history = "";
    private int cursor;
    private long sequence;
    private boolean closed;

    public ChatSessionStream() {
    }

    public boolean addUser(String user) {
        synchronized (monitor) {
            requireOpen();
            validateUser(user);

            if (findUser(user) >= 0) {
                return false;
            }
            if (users.length == 2147483647) {
                throw new IllegalStateException("User capacity exhausted");
            }

            String[] updated = new String[users.length + 1];
            for (int i = 0; i < users.length; i++) {
                updated[i] = users[i];
            }
            updated[users.length] = user;
            users = updated;
            return true;
        }
    }

    public boolean removeUser(String user) {
        synchronized (monitor) {
            requireOpen();
            validateUser(user);

            int index = findUser(user);
            if (index < 0) {
                return false;
            }

            String[] updated = new String[users.length - 1];
            for (int i = 0; i < index; i++) {
                updated[i] = users[i];
            }
            for (int i = index + 1; i < users.length; i++) {
                updated[i - 1] = users[i];
            }
            users = updated;
            return true;
        }
    }

    public long send(String user, String text) {
        synchronized (monitor) {
            requireOpen();
            return sendLocked(user, text);
        }
    }

    public long write(String command) {
        synchronized (monitor) {
            requireOpen();
            if (command == null) {
                throw new NullPointerException("command");
            }

            int delimiter = command.indexOf(": ");
            if (delimiter <= 0) {
                throw new IllegalArgumentException(
                        "Expected a nonempty username followed by ': ' and text");
            }

            String user = command.substring(0, delimiter);
            String text = command.substring(delimiter + 2);
            return sendLocked(user, text);
        }
    }

    // Reads never wait for messages. Storage grows subject to JVM limits.
    public String read(int maxChars) {
        synchronized (monitor) {
            if (maxChars <= 0) {
                throw new IllegalArgumentException("maxChars must be positive");
            }

            int available = history.length() - cursor;
            int count = maxChars < available ? maxChars : available;
            String result = history.substring(cursor, cursor + count);
            cursor += count;
            return result;
        }
    }

    public void seek(int position) {
        synchronized (monitor) {
            if (position < 0 || position > history.length()) {
                throw new IllegalArgumentException(
                        "position must be within the current history");
            }
            cursor = position;
        }
    }

    public void close() {
        synchronized (monitor) {
            closed = true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            String[] copiedUsers = new String[users.length];
            for (int i = 0; i < users.length; i++) {
                copiedUsers[i] = users[i];
            }

            Message[] copiedMessages = new Message[messages.length];
            for (int i = 0; i < messages.length; i++) {
                Message message = messages[i];
                copiedMessages[i] = new Message(
                        message.sequence, message.user, message.text);
            }

            return new Snapshot(
                    copiedUsers, copiedMessages, history, cursor, sequence, closed);
        }
    }

    private long sendLocked(String user, String text) {
        validateUser(user);
        validateText(text);

        if (findUser(user) < 0) {
            throw new IllegalArgumentException("User is not registered");
        }
        if (sequence == MAX_SEQUENCE) {
            throw new IllegalStateException("Sequence numbers exhausted");
        }
        if (messages.length == 2147483647) {
            throw new IllegalStateException("Message capacity exhausted");
        }

        long nextSequence = sequence + 1;
        Message message = new Message(nextSequence, user, text);
        Message[] updatedMessages = new Message[messages.length + 1];
        for (int i = 0; i < messages.length; i++) {
            updatedMessages[i] = messages[i];
        }
        updatedMessages[messages.length] = message;

        long newLength = (long) history.length()
                + user.length() + 2L + text.length() + 1L;
        if (newLength > 2147483647L) {
            throw new IllegalStateException("Text history capacity exhausted");
        }

        // Complete all allocating work before committing either history.
        String updatedHistory = history + user + ": " + text + "\n";
        messages = updatedMessages;
        history = updatedHistory;
        sequence = nextSequence;
        return nextSequence;
    }

    private int findUser(String user) {
        for (int i = 0; i < users.length; i++) {
            if (users[i].equals(user)) {
                return i;
            }
        }
        return -1;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
    }

    private static void validateUser(String user) {
        if (user == null) {
            throw new NullPointerException("user");
        }
        if (user.length() == 0) {
            throw new IllegalArgumentException("Username must not be empty");
        }
        for (int i = 0; i < user.length(); i++) {
            char character = user.charAt(i);
            if (character == ':' || character == '\r' || character == '\n') {
                throw new IllegalArgumentException(
                        "Username must not contain a colon, CR, or LF");
            }
        }
    }

    private static void validateText(String text) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\r' || character == '\n') {
                throw new IllegalArgumentException(
                        "Message text must not contain CR or LF");
            }
        }
    }

    public static final class Message {
        public final long sequence;
        public final String user;
        public final String text;

        private Message(long sequence, String user, String text) {
            this.sequence = sequence;
            this.user = user;
            this.text = text;
        }
    }

    public static final class Snapshot {
        // These arrays belong exclusively to this snapshot, not the session.
        public final String[] users;
        public final Message[] messages;
        public final String text;
        public final int cursor;
        public final long sequence;
        public final boolean closed;

        private Snapshot(
                String[] users,
                Message[] messages,
                String text,
                int cursor,
                long sequence,
                boolean closed) {
            this.users = users;
            this.messages = messages;
            this.text = text;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closed = closed;
        }
    }
}
