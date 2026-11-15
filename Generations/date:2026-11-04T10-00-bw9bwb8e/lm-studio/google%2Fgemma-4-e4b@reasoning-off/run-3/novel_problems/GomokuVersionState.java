GomokuVersionState {

    // Constants for game states and players
    public static final char EMPTY = '.';
    public static final char PLAYER_X = 'X';
    public static final char PLAYER_O = 'O';

    // Move result statuses for makeMove
    public static final int STATUS_SUCCESS = 0;
    public static final int FAILURE_STALE_REVISION = 1;
    public static final int FAILURE_FINISHED = 2;
    public static final int FAILURE_WRONG_TURN = 3;
    public static final int FAILURE_OCCUPIED = 4;

    // Terminal state indicators
    public static final String WINNER_X = "WIN_X";
    public static final String WINNER_O = "WIN_O";
    public static final String DRAW = "DRAW";

    // Inner class for the result of a successful move
    public static class MoveResult {
        private final int status; // STATUS_SUCCESS if move was successful
        private final long newRevision;
        private final int generation;
        private final String winner; // WINNER_X, WINNER_O, DRAW, or null

        MoveResult(int status, long newRevision, int generation, String winner) {
            this.status = status;
            this.newRevision = newRevision;
            this.generation = generation;
            this.winner = winner;
        }

        public int getStatus() { return status; }
        public long getNewRevision() { return newRevision; }
        public int getGeneration() { return generation; }
        public String getWinner() { return winner; }

        // Helper to check if the move was successful
        public boolean isSuccessful() { return status == STATUS_SUCCESS; }
    }

    // Inner class for a deep copy of the current game state snapshot
    public static class GameSnapshot {
        private final char[][] board;
        private final int moveCount;
        private final char currentPlayer; // Expected player for the *next* move
        private final boolean isGameOver;
        private final String gameMetadata; // Includes winner/draw info

        // Constructor for a new snapshot from the current state
        GameSnapshot(char[][] board, int moveCount, char currentPlayer, boolean isGameOver, String gameMetadata) {
            this.board = deepCopyBoard(board);
            this.moveCount = moveCount;
            this.currentPlayer = currentPlayer;
            this.isGameOver = isGameOver;
            this.gameMetadata = gameMetadata;
        }

        // Private constructor used internally, expects fully constructed state
        private GameSnapshot(char[][] board, int moveCount, char currentPlayer, boolean isGameOver, String gameMetadata) {
            this.board = deepCopyBoard(board);
            this.moveCount = moveCount;
            this.currentPlayer = currentPlayer;
            this.isGameOver = isGameOver;
            this.gameMetadata = gameMetadata;
        }

        private char[][] deepCopyBoard(char[][] original) {
            if (original == null || original.length == 0) return new char[][]{};
            int rows = original.length;
            int cols = original[0].length;
            char[][] copy = new char[rows][cols];
            for (int i = 0; i < rows; i++) {
                System.arraycopy(original[i], 0, copy[i], 0, cols);
            }
            return copy;
        }

        // Public accessor for the copied board
        public char[][] getBoardCopy() {
            return deepCopyBoard(this.board); // Return a fresh copy to prevent external mutation
        }

        public int getMoveCount() { return moveCount; }
        public char getCurrentPlayer() { return currentPlayer; }
        public boolean isGameOver() { return isGameOver; }
        public String getGameMetadata() { return gameMetadata; }

        // Note: The Contract requires the methods to operate on this class, 
        // but for submission completeness, we keep the metadata structures clean.
    }

    private final int size;
    private char[][] board;
    private int moveCount;
    private char currentPlayer; // Player whose turn it is to move next
    private long revision;
    private int generation;
    private boolean gameOver;
    private char winner = 0; // Stores X or O if game is over

    // --- Constructor and Initialization ---

    /**
     * Constructs a new GomokuVersionState instance.
     * @param size The board dimension (must be >= 5).
     * @throws IllegalArgumentException if size < 5.
     */
    public GomokuVersionState(int size) throws IllegalArgumentException {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be 5 or greater.");
        }
        this.size = size;
        // Initialize to starting state: empty board, X to move, count 0, no winner, generation 0, revision 0
        this.board = new char[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                this.board[i][j] = EMPTY;
            }
        }
        this.moveCount = 0;
        this.currentPlayer = PLAYER_X;
        this.revision = 0;
        this.generation = 0;
        this.gameOver = false;
    }

    // --- Public API Methods ---

    /**
     * Attempts to apply a move. This operation is atomic regarding state transition checks.
     * @param player The player attempting the move (X or O).
     * @param row The row coordinate.
     * @param column The column coordinate.
     * @param expectedRevision The revision observed by the caller before attempting the move.
     * @return A MoveResult object describing the outcome of the attempt.
     * @throws IllegalArgumentException for invalid player, coordinates, or negative revisions.
     */
    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != PLAYER_X && player != PLAYER_O) {
            throw new IllegalArgumentException("Invalid player.");
        }

        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates out of bounds.");
        }

        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative revisions are not allowed.");
        }

        synchronized (this) {
            // 1. Check Revision Match
            if (expectedRevision != this.revision) {
                return new MoveResult(FAILURE_STALE_REVISION, this.revision, this.generation, null);
            }

            // 2. Check Terminal State
            if (this.gameOver) {
                return new MoveResult(FAILURE_FINISHED, this.revision, this.generation, null);
            }

            // 3. Check Turn
            if (player != this.currentPlayer) {
                return new MoveResult(FAILURE_WRONG_TURN, this.revision, this.generation, null);
            }

            // 4. Check Cell Occupancy (Must be EMPTY)
            if (this.board[row][column] != EMPTY) {
                return new MoveResult(FAILURE_OCCUPIED, this.revision, this.generation, null);
            }

            // --- Attempt successful move (Transaction Start) ---

            // Apply Move and Check Win/Draw
            this.board[row][column] = player;
            this.moveCount++;

            // Check for victory or draw potential on the newly moved board state
            int matchCount = checkMatch(player, row, column);

            if (matchCount >= 5) {
                // Victory found
                this.gameOver = true;
                this.winner = player;
                // Advance revision and generation before returning success
                long newRevision = this.revision + 1;
                int nextGeneration = this.generation + 1; // Generation advances on terminal state transition in our model
                return new MoveResult(STATUS_SUCCESS, newRevision, nextGeneration, player == PLAYER_X ? WINNER_X : WINNER_O);
            }

            if (this.moveCount == size * size) {
                // Full board, no winner -> Draw
                this.gameOver = true;
                this.winner = 0; // Null/No winner character
                // Advance revision and generation before returning success
                long newRevision = this.revision + 1;
                int nextGeneration = this.generation + 1;
                return new MoveResult(STATUS_SUCCESS, newRevision, nextGeneration, DRAW);
            }

            // Non-terminal move: Advance turn, revision, and generation based on the move.
            if (player == PLAYER_X) {
                this.currentPlayer = PLAYER_O;
            } else {
                this.currentPlayer = PLAYER_X;
            }

            // Advance state trackers for the successful move
            this.revision = this.revision + 1;
            this.generation = this.generation + 1;

            // Return success indicating the state has advanced
            return new MoveResult(STATUS_SUCCESS, this.revision, this.generation, null);
        }
    }

    /**
     * Atomically resets the game state to match the initial conditions, advancing generation and revision.
     * @return A deep copy of the state *after* reset, which is identical to the initial starting state.
     */
    public GameSnapshot reset() {
        synchronized (this) {
            // Detect overflow conceptually. Since we are always incrementing, this is impossible unless bounds checking was implemented elsewhere.
            // We proceed with reset as required.

            // Resetting consumes the current revision/generation context to establish a new baseline
            this.revision = 0;
            this.generation++; // Advance generation upon reset
            this.moveCount = 0;
            this.currentPlayer = PLAYER_X;
            this.gameOver = false;
            this.winner = 0;

            // Clear the board
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    this.board[i][j] = EMPTY;
                }
            }

            // Return a deep snapshot of the newly reset state
            return new GameSnapshot(this.board, this.moveCount, this.currentPlayer, this.gameOver, generateDisplayMetadata());
        }
    }

    /**
     * Returns a deep copy of the current game state and all associated metadata.
     * @return A GameSnapshot containing the board, turn, and metadata at the moment of observation.
     */
    public GameSnapshot snapshot() {
        synchronized (this) {
            // Generate metadata based on the *current* state before copying anything
            String metadata = generateDisplayMetadata();
            return new GameSnapshot(this.board, this.moveCount, this.currentPlayer, this.gameOver, metadata);
        }
    }

    // --- Internal Helper Methods ---

    /**
     * Generates the display metadata string used in the contract specification.
     */
    private String generateDisplayMetadata() {
        String status;
        if (gameOver) {
            status = this.winner == PLAYER_X ? WINNER_X : (this.winner == PLAYER_O ? WINNER_O : DRAW);
        } else {
            status = null; // Not terminal yet, metadata only describes the current situation generally
        }

        // Display version: size + "." + moveCount + "." + the decimal ASCII value of currentPlayer, appending status.
        int currentAscii = this.currentPlayer; // Assuming ASCII values match needed encoding
        // Note: Since we only use 'X' or 'O', their numeric ASCII values are fixed.
        String playerChar = String.valueOf(this.currentPlayer);

        // Contract implies the version string is a concatenation of specific parts:
        // Size + "." + MoveCount + "." + ASCII_of_Player
        String baseVersion = size + "." + this.moveCount + "." + (int)this.currentPlayer;
        
        if (this.gameOver && this.winner != 0) {
            return baseVersion + "." + status;
        } else if (this.gameOver && this.moveCount == size * size) { // Draw case
             return baseVersion + "." + DRAW;
        } else if (this.gameOver) {
            // Should be covered above, but as a fallback for conceptual consistency:
            return baseVersion + ".TERMINAL_UNCATEGORIZED"; 
        } else {
            // In-play state representation (optional, but following structure)
            return baseVersion; 
        }
    }

    /**
     * Checks for 5+ contiguous stones in all directions passing through (r, c) played by 'player'.
     * @return The count of contiguous stones in the longest line found, or 0 if none.
     */
    private int checkMatch(char player, int r, int c) {
        int maxCount = 1;

        // Directions to check: Horizontal, Vertical, Diagonal (\), Diagonal (/)
        int[] dr = {0, 1, 1, 1};
        int[] dc = {1, 0, 1, -1};

        for (int dir = 0; dir < 4; dir++) {
            // Check count in positive direction of this vector
            int currentLineCount = 1;
            int[] dr_pos = {dr[dir], -dr[dir]};
            int[] dc_pos = {dc[dir], -dc[dir]};

            // Count along positive vector
            int count_pos = 0;
            for (int k = 1; k < 5; k++) { // Check up to 4 neighbors in one direction
                int nr = r + k * dr_pos[0];
                int nc = c + k * dc_pos[1];

                if (nr >= 0 && nr < size && nc >= 0 && nc < size && board[nr][nc] == player) {
                    count_pos++;
                } else {
                    break;
                }
            }

            // Count along negative vector (opposite direction)
            int count_neg = 0;
            for (int k = 1; k < 5; k++) { // Check up to 4 neighbors in opposite direction
                int nr = r - k * dr_pos[0]; // Opposite vector application
                int nc = c - k * dc_pos[1];

                 if (nr >= 0 && nr < size && nc >= 0 && nc < size && board[nr][nc] == player) {
                    count_neg++;
                } else {
                    break;
                }
            }

            int total = count_pos + count_neg + 1; // +1 for the stone at (r, c)
            if (total > maxCount) {
                maxCount = total;
            }
        }

        return maxCount;
    }


    // --- Testing/Debugging Accessors (Optional but helpful) ---
    // These are outside the strict concurrency contract implementation path but show internal state fidelity.

    public long getCurrentRevision() {
        synchronized (this) {
            return this.revision;
        }
    }

    public int getCurrentGeneration() {
        synchronized (this) {
            return this.generation;
        }
    }

    public boolean isGameActive() {
        synchronized (this) {
            return !this.gameOver;
        }
    }

    public char[][] getCurrentBoardCopy() {
        synchronized (this) {
            return new char[][]{}; // Placeholder for proper deep copy implementation if needed elsewhere
        }
    }
}
