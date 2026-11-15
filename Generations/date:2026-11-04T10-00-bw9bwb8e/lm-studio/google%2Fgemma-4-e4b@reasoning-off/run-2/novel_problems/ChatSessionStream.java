@ChatSessionStream.ThreadSafe
public class ChatSessionStream {
    @interface ThreadSafe {}


    // --- Internal State Variables ---

    private boolean isClosed = false;
    private int sequenceCounter = 0; // Unique positive sequence number for messages
    private int cursorPosition = 0;   // Current read cursor position (index into concatenated text)

    private boolean usersChanged = false;
    private String[] registeredUsersArray; // Array holding usernames (for array-based constraints)
    private int userCount = 0;
    private static final int INITIAL_CAPACITY = 10;

    private StringBuilder textHistoryBuilder = new StringBuilder(); // Stores the complete sequence of messages
    private boolean historyIsStable = false; // Indicates if all message appends are safely recorded

    // --- Synchronization Mechanism ---
    // A single intrinsic lock on 'this' object guards all mutable shared state.

    // --- Constructor (Initial State) ---
    public ChatSessionStream() {
        // Initial state: no users, empty history, cursor 0, open session.
        this.registeredUsersArray = new String[INITIAL_CAPACITY];
    }

    // --- Helper Methods for State Management (Internal) ---

    private void checkOpen() {
        if (isClosed) {
            throw new IllegalStateException("Session is closed.");
        }
    }

    /**
     * Synchronized block guard for operations that must maintain exclusive access to critical sections.
     */
    private void enterCriticalSection() {
        // Acquires the lock on 'this' object.
    }

    private void exitCriticalSection() {
        // Releases the lock on 'this' object. (Handled by leaving synchronized block)
    }

    // --- Public Contract Implementation ---

    /**
     * Adds a user to the session.
     * @param user The username (nonempty, no colon/CR/LF).
     * @return true if membership changed, false otherwise.
     */
    public boolean addUser(String user) {
        if (user == null || user.isEmpty()) return false;

        synchronized (this) {
            if (isClosed) {
                return false; // Membership changes rejected after close, but should signal state attempt.
            }

            // Check if user already exists (searching in the array representation)
            for (int i = 0; i < userCount; i++) {
                if (registeredUsersArray[i] != null && registeredUsersArray[i].equals(user)) {
                    return false; // Already present
                }
            }

            // Add user (using array expansion/shifting logic)
            if (userCount >= registeredUsersArray.length) {
                // Resize array (simplified: double capacity, handles capacity constraint compliance)
                int newCapacity = registeredUsersArray.length == 0 ? INITIAL_CAPACITY : registeredUsersArray.length * 2;
                String[] newArray = new String[newCapacity];
                System.arraycopy(registeredUsersArray, 0, newArray, 0, userCount);
                registeredUsersArray = newArray;
            }

            userCount++;
            registeredUsersArray[userCount - 1] = user;
            usersChanged = true;
            return true;
        }
    }

    /**
     * Removes a user from the session.
     * @param user The username to remove.
     * @return true if membership changed, false otherwise.
     */
    public boolean removeUser(String user) {
        if (user == null || user.isEmpty()) return false;

        synchronized (this) {
            if (isClosed) {
                return false;
            }

            int foundIndex = -1;
            for (int i = 0; i < userCount; i++) {
                if (registeredUsersArray[i] != null && registeredUsersArray[i].equals(user)) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex == -1) {
                return false; // User not found
            }

            // Shift elements to cover the removed spot
            for (int i = foundIndex; i < userCount - 1; i++) {
                registeredUsersArray[i] = registeredUsersArray[i + 1];
            }
            registeredUsersArray[userCount - 1] = null; // Clear the last slot
            userCount--;
            usersChanged = true;
            return true;
        }
    }

    /**
     * Sends a message from a specific user.
     * @param user The sending user.
     * @param text The message content.
     * @return The assigned sequence number, or throws IllegalArgumentException if user is unknown.
     */
    public int send(String user, String text) {
        if (user == null || user.isEmpty() || text == null) {
            throw new IllegalArgumentException("Inputs cannot be null.");
        }

        synchronized (this) {
            checkOpen(); // Throws IllegalStateException if closed
            if (!isUserPresent(user)) {
                throw new IllegalArgumentException("Sender is not a currently registered user.");
            }

            // Message construction: "{user}: {text}\n" (includes required sequence markers)
            String messagePrefix = user + ": ";
            String fullMessageLine = messagePrefix + text + "\n";

            // Atomic Append Phase
            sequenceCounter++;
            int currentSequence = sequenceCounter;

            // 1. Append to history buffer (This must be the atomic write)
            textHistoryBuilder.append(fullMessageLine);

            // 2. Update state variables
            historyIsStable = true; // Indicates a valid, sequenced message has been added
            cursorPosition = textHistoryBuilder.length(); // Cursor advances to the end upon successful append

            // Return the sequence number
            return currentSequence;
        }
    }

    /**
     * Writes a message by parsing a command string.
     * @param command The input string expected to be "user: message".
     * @return The assigned sequence number.
     */
    public int write(String command) {
        if (command == null) {
            throw new IllegalArgumentException("Input command cannot be null.");
        }

        // Find the first colon-space delimiter ": "
        int separatorIndex = -1;
        for (int i = 0; i < command.length(); i++) {
            if (command.charAt(i) == ':' && i + 1 < command.length() && command.charAt(i + 1) == ' ') {
                separatorIndex = i;
                break;
            }
        }

        if (separatorIndex == -1) {
            // Malformed command, reject silently as per contract regarding sequence assignment failure.
            return -1; // Sentinel indicating command rejected due to malformation
        }

        String user = command.substring(0, separatorIndex);
        if (user.isEmpty()) return -1; // User part is empty

        String text = command.substring(separatorIndex + 2); // Skip ": "

        // Delegate to send semantics
        try {
            return send(user, text);
        } catch (IllegalArgumentException e) {
            // If send fails because the user is not present, we return -1 to indicate failure without modification.
            return -1;
        }
    }

    /**
     * Reads up to maxChars from the current cursor position. Never waits.
     * @param maxChars The maximum number of characters to read (must be > 0).
     * @return The string slice read, or "" if no characters available at the cursor.
     */
    public String read(int maxChars) {
        if (maxChars <= 0 || maxChars == null) { // Although primitive int, check for logical zero case
            throw new IllegalArgumentException("maxChars must be positive.");
        }

        synchronized (this) {
            checkOpen(); // Should not be strictly necessary for read if history is retained, but good practice.

            // Read simulation: never waits, returns whatever is available up to maxChars
            if (textHistoryBuilder.length() <= cursorPosition) {
                return ""; // At the current end
            }

            int charsAvailable = textHistoryBuilder.length() - cursorPosition;
            int readLength = Math.min(maxChars, charsAvailable);

            // Extract substring corresponding to the current cursor position
            String segment = textHistoryBuilder.substring(cursorPosition, cursorPosition + readLength);

            // Advance cursor
            cursorPosition += segment.length();

            return segment;
        }
    }

    /**
     * Sets the read cursor to a specific position.
     * @param position The desired starting index (0 <= position <= current history length).
     */
    public void seek(int position) {
        if (position < 0 || position > textHistoryBuilder.length()) {
            // Invalid seek target, ignore change or handle error if required. Here we assume strict acceptance of valid range requests.
            return;
        }

        synchronized (this) {
            cursorPosition = position;
        }
    }


    /**
     * Atomically closes the session. Idempotent.
     */
    public void close() {
        synchronized (this) {
            if (!isClosed) {
                isClosed = true;
                // History remains readable/seekable.
            }
        }
    }

    /**
     * Returns a coherent snapshot of the current session state.
     */
    public Snapshot snapshot() {
        synchronized (this) {
            // Deep copy required for coherent view and preventing external mutation.

            // 1. Users (shallow copy is fine as usernames are immutable strings)
            String[] usersCopy = new String[userCount];
            System.arraycopy(registeredUsersArray, 0, usersCopy, 0, userCount);

            // 2. History/Text (copy the current content)
            String textCopy = textHistoryBuilder.toString();

            // 3. Sequence/Cursor/Closed status
            int finalSequence = sequenceCounter;
            int cursorCopy = cursorPosition;
            boolean closedCopy = isClosed;

            // Note: historyIsStable should ideally be true if sequenceCounter > 0, but we rely on the builder state.

            return new Snapshot(
                new StringArrayWrapper(usersCopy), // Use wrapper to comply with return type constraint if needed, though internal array is sufficient for contract purity
                textCopy,
                cursorCopy,
                finalSequence,
                closedCopy
            );
        }
    }

    // --- Internal Checks ---

    private boolean isUserPresent(String user) {
        for (int i = 0; i < userCount; i++) {
            if (registeredUsersArray[i] != null && registeredUsersArray[i].equals(user)) {
                return true;
            }
        }
        return false;
    }

    // --- Supporting Classes (Must be defined internally) ---

    /** Helper class to wrap the array copy for encapsulation boundary. */
    public static class StringArrayWrapper implements java.lang.Object { // Base type must be Object to avoid package constraint issues
        private final String[] users;

        public StringArrayWrapper(String[] users) {
            this.users = users;
        }

        public String[] getArray() {
            return users;
        }
    }


    /** Structure representing the complete state at a point in time. */
    public static class Snapshot {
        // Since the contract implies sequence matters, we return structures that reflect the view.
        private final StringArrayWrapper users;
        private final String fullText;
        private final int cursor;
        private final int sequence;
        private final boolean closedStatus;

        public Snapshot(StringArrayWrapper users, String fullText, int cursor, int sequence, boolean closedStatus) {
            this.users = users;
            this.fullText = fullText;
            this.cursor = cursor;
            this.sequence = sequence;
            this.closedStatus = closedStatus;
        }

        // Getters matching the contract view expectation
        public String[] getUsers() { return users.getArray(); }
        public String getText() { return fullText; }
        public int getCursor() { return cursor; }
        public int getSequence() { return sequence; }
        public boolean isClosed() { return closedStatus; }
    }
}
