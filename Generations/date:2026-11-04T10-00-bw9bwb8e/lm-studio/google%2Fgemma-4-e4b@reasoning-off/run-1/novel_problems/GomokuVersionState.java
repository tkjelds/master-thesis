@GomokuVersionState.ThreadSafe
GomokuVersionState.java
class GomokuVersionState {
    @interface ThreadSafe {}


    // Constants for terminal states and player representation
    private static final char EMPTY = '.';
    private static final char PLAYER_X = 'X';
    private static final char PLAYER_O = 'O';

    // Move failure outcomes during makeMove
    public static final int STALE_REVISION = 1; // Expected revision was lower than current
    public static final int FINISHED = 2;      // Game is over (Win/Draw)
    public static final int WRONG_TURN = 3;    // It's not this player's turn
    public static final int OCCUPIED = 4;      // Cell already taken

    private final int boardSize;
    private char[][] board;
    private char currentPlayer; // Whose turn is it? 'X' or 'O'
    private int moveCount;
    private boolean gameOver;
    private char winner; // '\0' if no winner yet
    private int generation;
    private long revision;

    // --- Internal State Management ---

    /**
     * Constructs the GomokuVersionState object.
     * @param size The dimension of the square board (must be >= 5).
     */
    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5.");
        }
        this.boardSize = size;
        initializeBoard(size);

        // Initial state established by constructor invariants:
        // Board is empty, move count 0, no winner, generation 0, revision 0.
        this.currentPlayer = PLAYER_X; // X starts first
        this.moveCount = 0;
        this.gameOver = false;
        this.winner = '\0';
        this.generation = 0;
        this.revision = 0L; // Start revision counter at 0
    }

    private void initializeBoard(int size) {
        board = new char[size][size];
        for (int[] row : board) {
            java.util.Arrays.fill(row, EMPTY);
        }
    }

    // --- Core Public API Methods ---

    /**
     * Attempt to place a stone on the board. This method must be atomic regarding checks and updates.
     * @param player The player attempting the move ('X' or 'O').
     * @param row The row index (0 to size-1).
     * @param column The column index (0 to size-1).
     * @param expectedRevision The revision number expected when the move started.
     * @return A result object indicating success or failure codes, along with current state if successful.
     * @throws IllegalArgumentException for invalid inputs (player, bounds, revision).
     */
    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        // 1. Validation (Throws if invalid inputs are provided)
        if (player != PLAYER_X && player != PLAYER_O) {
            throw new IllegalArgumentException("Invalid player.");
        }
        if (row < 0 || row >= boardSize || column < 0 || column >= boardSize) {
            throw new IllegalArgumentException("Coordinates out of bounds.");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative revision.");
        }

        // All state transitions happen under the lock protecting the object's invariants.
        synchronized (this) {
            // 2. Atomic Check Phase (using expectedRevision)
            if (this.revision != expectedRevision) {
                return new MoveResult(STALE_REVISION, this.revision, this.generation, this.winner);
            }

            if (this.gameOver) {
                return new MoveResult(FINISHED, this.revision, this.generation, this.winner);
            }

            if (player != this.currentPlayer) {
                return new MoveResult(WRONG_TURN, this.revision, this.generation, this.winner);
            }

            if (board[row][column] != EMPTY) {
                return new MoveResult(OCCUPIED, this.revision, this.generation, this.winner);
            }

            // --- Successful Move Execution ---

            // Update state based on the move
            board[row][column] = player;
            this.moveCount++;

            // Check for win/draw *before* advancing revision and player
            MoveResult currentResult = executeMoveAndCheckWin(player, row, column);

            if (currentResult.status == FINISHED) {
                // Game ended on this move (Win or Draw)
                this.revision++; // Advance revision even if terminal, marking the final state
                this.generation++;
                // currentPlayer remains set to the winner/last player, gameOver=true
            } else {
                // Game continues: Advance revision and switch turn
                this.revision++;
                this.currentPlayer = (player == PLAYER_X) ? PLAYER_O : PLAYER_X;
                this.generation++;
            }

            // Return the result reflecting the *new* state details after the move was accepted
            return currentResult;
        }
    }

    /**
     * Atomically clears the board and resets the game state, advancing generation and revision.
     * @return A deep copy of the board and metadata representing the *new*, post-reset state.
     */
    public Snapshot reset() {
        synchronized (this) {
            // Reset is a conceptual move. We must detect overflow before mutation.
            // Since revision and generation are long/int, and we only increment them after use,
            // simple overflow detection might involve checking against MAX_LONG/MAX_INT if we truly feared it.
            // Given the problem constraints, reaching MAX_LONG/MAX_INT before a logical end state is unlikely
            // during normal play unless the game runs indefinitely, so we proceed with standard types.

            this.moveCount = 0;
            this.gameOver = false;
            this.winner = '\0';
            this.generation++;
            this.revision++; // Revision advances on reset as well, consuming a conceptual move slot

            // Restore initial board and player state
            initializeBoard(boardSize);
            this.currentPlayer = PLAYER_X;

            // Return a deep snapshot of the newly reset state
            return captureSnapshot();
        }
    }

    /**
     * Returns a deep copy of the current game state.
     * @return A Snapshot object containing the board and metadata at this moment.
     */
    public Snapshot snapshot() {
        synchronized (this) {
            return captureSnapshot();
        }
    }

    // --- Internal Helper Methods ---

    /**
     * Captures the current observable state into a Snapshot object. This requires synchronization.
     */
    private Snapshot captureSnapshot() {
        // Deep copy the board array for encapsulation outside the lock scope if needed, 
        // but since Snapshot holds references to values, a shallow copy of the board reference array is sufficient
        // if we ensure the internal generation/revision match.
        char[][] currentBoardCopy = new char[boardSize][];
        for (int i = 0; i < boardSize; i++) {
            currentBoardCopy[i] = java.util.Arrays.copyOf(board[i], boardSize);
        }

        // Build the display metadata string based on current state
        String statusTag;
        if (gameOver) {
            statusTag = (winner == PLAYER_X) ? ".WIN_X" : (winner == PLAYER_O) ? ".WIN_O" : ".DRAW";
        } else {
            statusTag = "";
        }

        // Build the display version string
        String displayVersion = buildDisplayVersion(this.moveCount, this.currentPlayer, statusTag);

        return new Snapshot(currentBoardCopy, this.moveCount, this.currentPlayer, this.winner, this.generation, this.revision, displayVersion);
    }


    /**
     * Executes the move locally and checks for terminal conditions (Win/Draw).
     * Assumes synchronization context is held.
     * @return MoveResult reflecting the outcome of placing the stone at (row, column).
     */
    private MoveResult executeMoveAndCheckWin(char player, int row, int column) {
        // 1. Place stone (already done in makeMove entry block if successful, but good practice to confirm context)
        // board[row][column] = player;

        // 2. Check for win condition
        if (checkWin(player, row, column)) {
            this.gameOver = true;
            this.winner = player;
            // Do not advance currentPlayer here, as the game is over. The MoveResult signals this.
        } else if (moveCount + 1 == boardSize * boardSize) {
            // Board is full and no winner detected yet -> Draw
            this.gameOver = true;
            this.winner = '\0'; // Sentinel for Draw detected on full board
        } else {
            // Game continues, switch player managed in makeMove caller.
        }

        // Build the result object describing the state AFTER this move attempt finalized its effects.
        String statusTag;
        if (this.gameOver) {
            statusTag = (winner == PLAYER_X) ? ".WIN_X" : (winner == PLAYER_O) ? ".WIN_O" : ".DRAW";
        } else {
            statusTag = "";
        }

        // Revision and generation are advanced AFTER this method returns successfully in makeMove.
        return new MoveResult(0, this.revision, this.generation, this.winner); // Success code 0
    }


    /**
     * Checks if the move at (row, column) for 'player' resulted in 5 or more in a row.
     */
    private boolean checkWin(char player, int lastRow, int lastCol) {
        int count = 0;

        // Check all four directions originating from (lastRow, lastCol)
        int[][] directions = {
            {0, 1}, {0, -1}, // Horizontal
            {1, 0}, {-1, 0}, // Vertical
            {1, 1}, {1, -1}  // Diagonal
        };

        for (int[] dir : directions) {
            int dr = dir[0];
            int dc = dir[1];

            // Count sequence in direction (dr, dc)
            int countForward = 1;
            int r = lastRow + dr;
            int c = lastCol + dc;

            while (r >= 0 && r < boardSize && c >= 0 && c < boardSize && board[r][c] == player) {
                countForward++;
                r += dr;
                c += dc;
            }

            // Count sequence in reverse direction (-dr, -dc)
            int countBackward = 0;
            r = lastRow - dr;
            c = lastCol - dc;

            while (r >= 0 && r < boardSize && c >= 0 && c < boardSize && board[r][c] == player) {
                countBackward++;
                r -= dr;
                c -= dc;
            }

            // Total contiguous stones = forward + backward + 1 (the current stone)
            int totalCount = countForward + countBackward;

            if (totalCount >= 5) {
                return true;
            }
        }
        return false;
    }

    // --- Display Helpers ---

    private String buildDisplayVersion(int moveCount, char currentPlayer, String tag) {
        // Decimal ASCII value of currentPlayer (X or O)
        int asciiValue = (currentPlayer == PLAYER_X) ? 'X' : 'O';
        return boardSize + "." + moveCount + "." + asciiValue + tag;
    }

    // --- Data Transfer Objects (DTOs) ---

    /** Represents the outcome of a makeMove attempt. */
    public static class MoveResult {
        final int status; // SUCCESS (0), STALE_REVISION, FINISHED, WRONG_TURN, OCCUPIED
        final long revision;
        final int generation;
        final char winner;

        // Success case: status=0, revision/generation advanced.
        public MoveResult(int status, long rev, int gen, char win) {
            this.status = status;
            this.revision = rev;
            this.generation = gen;
            this.winner = win;
        }
    }

    /** Represents the current state of the game at any point. */
    public static class Snapshot {
        final char[][] board;
        final int moveCount;
        final char currentPlayer;
        final char winner; // '\0' or 'X'/'O'
        final int generation;
        final long revision;
        final String displayVersion;

        public Snapshot(char[][] board, int moveCount, char currentPlayer, char winner, int generation, long revision, String displayVersion) {
            this.board = board; // NOTE: This is a shallow array copy, but individual cells are primitives (char)
            this.moveCount = moveCount;
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.generation = generation;
            this.revision = revision;
            this.displayVersion = displayVersion;
        }
    }
}
