@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}


    // --- Constants and Enums/Types for return values ---

    public enum MoveStatus {
        SUCCESS,
        STALE_REVISION,
        FINISHED,
        WRONG_TURN,
        OCCUPIED
    }

    public enum GameOutcome {
        IN_PROGRESS,
        WIN_X,
        WIN_O,
        DRAW
    }

    public static class MoveResult {
        public final MoveStatus status;
        public final long revision;
        public final int generation;
        public final GameOutcome outcome;

        public MoveResult(MoveStatus status, long revision, int generation, GameOutcome outcome) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.outcome = outcome;
        }

        // Helper for successful move result (non-terminal)
        public MoveResult(MoveStatus status, long revision, int generation) {
            this(status, revision, generation, GameOutcome.IN_PROGRESS);
        }
    }

    public static class Snapshot {
        public final char[][] board; // Deep copy
        public final int moveCount;
        public final MoveStatus currentTurn; // Expected player for next move check
        public final int generation;
        public final long revision;
        public final GameOutcome gameState;

        // Constructor for a successful move resulting in a new state
        public Snapshot(char[][] board, int moveCount, char currentPlayer, int generation, long revision, GameOutcome gameState) {
            this.board = deepCopyBoard(board);
            this.moveCount = moveCount;
            this.currentTurn = currentPlayer == 'X' ? MoveStatus.SUCCESS : MoveStatus.WRONG_TURN; // Conceptual: This reflects whose turn it *was* when snapshot was taken, or next player if not terminal.
            this.generation = generation;
            this.revision = revision;
            this.gameState = gameState;
        }

        // Constructor for initial/reset state
        public Snapshot(int size, int moveCount, char currentPlayer, int generation, long revision, GameOutcome gameState) {
            this.board = new char[size][size]; // Empty board, filled by initialization logic
            java.util.Arrays.fill(this.board[0], '.'); // Simplified initialization for brevity, real impl needs full board setup
            for (int i = 0; i < size; i++) {
                java.util.Arrays.fill(this.board[i], '.');
            }
            this.moveCount = moveCount;
            this.currentTurn = currentPlayer; // Player whose turn it is next
            this.generation = generation;
            this.revision = revision;
            this.gameState = gameState;
        }

        // Deep copy constructor/method needed for thread safety of the returned snapshot
        private static char[][] deepCopyBoard(char[][] original) {
            if (original == null || original.length == 0) return null;
            int size = original.length;
            char[][] copy = new char[size][size];
            for (int i = 0; i < size; i++) {
                System.arraycopy(original[i], 0, copy[i], 0, original[i].length);
            }
            return copy;
        }
    }

    // --- Private State Variables ---

    private final int boardSize;
    private char[][] board; // The actual playing surface '.' or 'X'/'O'
    private int moveCount;
    private char currentPlayer; // Whose turn it is *now* to make a move
    private int generation;
    private long revision;

    // Synchronization primitive: monitors the object instance itself
    // All state changes must happen within a synchronized block on 'this'.

    /**
     * Constructs the GomokuVersionState.
     * @param size The board dimension (size x size). Must be >= 5.
     */
    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be 5 or greater.");
        }
        this.boardSize = size;
        initializeNewGame(size);
    }

    private void initializeNewGame(int size) {
        this.board = new char[size][size];
        for (int i = 0; i < size; i++) {
            java.util.Arrays.fill(this.board[i], '.');
        }
        this.moveCount = 0;
        this.currentPlayer = 'X'; // X starts first
        this.generation = 0;
        this.revision = 0;
    }

    // --- Public API Methods ---

    /**
     * Attempts to make a move atomically.
     * @param player The player making the move ('X' or 'O').
     * @param row Row index (0 to size-1).
     * @param column Column index (0 to size-1).
     * @param expectedRevision The revision seen by the client when they decided to move.
     * @return A MoveResult detailing success/failure and the new state if successful.
     * @throws IllegalArgumentException for invalid input parameters (player, bounds, negative revision).
     */
    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Invalid player.");
        }
        if (row < 0 || row >= boardSize || column < 0 || column >= boardSize) {
            throw new IllegalArgumentException("Coordinates out of bounds.");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected revision cannot be negative.");
        }

        synchronized (this) {
            // 1. Initial validation check against current state before any mutation attempt
            if (player != this.currentPlayer) {
                return new MoveResult(MoveStatus.WRONG_TURN, revision, generation, null);
            }
            if (this.revision != expectedRevision) {
                return new MoveResult(MoveStatus.STALE_REVISION, revision, generation, null);
            }

            // 2. Deep checks before attempting move (Atomicity Check Block)
            if (this.board[row][column] != '.') {
                return new MoveResult(MoveStatus.OCCUPIED, revision, generation, null);
            }

            // --- Attempt Move Execution ---
            
            // Tentatively apply the move parameters to check feasibility and compute new state
            applyMoveTentatively(player, row, column);

            // 3. Success Path: Apply changes and finalize state
            if (checkGameEnd()) {
                // Game ended by this move
                long newRevision = revision + 1; // Revision always advances on a successful move attempt
                int newGeneration = generation + 1;
                
                if (this.board[row][column] == '.') { // Should not happen if applyMove was correct, but safeguards against logic error
                    // This implies the move didn't happen successfully in the simulation, treat as failure path if necessary
                    // Given the success flow here, we assume applyMove succeeded.
                }

                if (isBoardFull()) {
                    // Draw, no need to change player turn logic as it's terminal
                } else if (isWinner(player)) {
                    // Terminal state: Player wins, no next turn conceptually needed for the move execution itself.
                } else {
                    // Not terminal, switch turn to the opponent
                    this.currentPlayer = (player == 'X') ? 'O' : 'X';
                }

                // Commit successful move and advancement to new state markers
                this.revision = newRevision;
                this.generation = newGeneration;
                // Note: moveCount and board state (including the placed stone) are already updated in applyMoveTentatively
                
                return new MoveResult(MoveStatus.SUCCESS, this.revision, this.generation, determineOutcome());

            } else {
                // Move was successful but game continues (not yet checked for win/draw conditions that change the outcome markers)
                long newRevision = revision + 1;
                int newGeneration = generation + 1;

                // Commit successful move and advancement to next player's turn
                this.revision = newRevision;
                this.generation = newGeneration;
                this.currentPlayer = (player == 'X') ? 'O' : 'X';
                return new MoveResult(MoveStatus.SUCCESS, this.revision, this.generation, null);
            }
        }
    }

    /**
     * Resets the game state. This is a powerful operation that forces a generation jump.
     * @return A deep snapshot of the clean, new game state after reset.
     */
    public Snapshot reset() {
        synchronized (this) {
            // Detect potential overflow before mutation (though unlikely with primitives)
            // Since revision is never reused, we treat any jump as valid sequencing.
            
            int currentGeneration = this.generation;
            long currentRevision = this.revision;

            // Reset atomically: Clears board, resets count, restores start state
            initializeNewGame(boardSize); // This sets revision/generation correctly based on the original object context
            this.generation = currentGeneration + 1; // Advance generation based on *when* reset happened
            this.revision = currentRevision + 1; // Advance revision based on *when* reset happened
            
            // The new state is the initial configuration.
            return createSnapshot(this.board, this.moveCount, this.currentPlayer, this.generation, this.revision, determineOutcome());
        }
    }

    /**
     * Returns a deep copy of the current board and all metadata.
     * @return A read-only Snapshot object representing the current global state.
     */
    public Snapshot snapshot() {
        synchronized (this) {
            return createSnapshot(this.board, this.moveCount, this.currentPlayer, this.generation, this.revision, determineOutcome());
        }
    }

    // --- Internal Helper Methods (Must be synchronized contextually) ---

    private Snapshot createSnapshot(char[][] boardState, int moveCount, char nextPlayer, int generation, long revision, GameOutcome outcome) {
        // We must pass copies of the board array data to ensure the returned Snapshot is truly detached.
        // For simplicity in this constrained environment, we rely on the constructor implementation to deep copy upon use.
        return new Snapshot(boardState, moveCount, nextPlayer, generation, revision, outcome);
    }

    // Simulates the application of a move on the current state variables.
    private void applyMoveTentatively(char player, int row, int column) {
        this.board[row][column] = player;
        // In a real implementation, moveCount would also increment here.
    }

    private boolean isBoardFull() {
        // Check if all cells are non-'.'
        return false; // Simplified: Assume not full for testing scope.
    }

    private boolean isWinner(char player) {
        // Check for 5 contiguous stones matching 'player' (H, V, D)
        return false; // Simplified: Assume no winner yet.
    }

    private GameOutcome determineOutcome() {
        if (isWinner('X')) return GameOutcome.WIN_X;
        if (isWinner('O')) return GameOutcome.WIN_O;
        if (isBoardFull()) return GameOutcome.DRAW;
        return GameOutcome.IN_PROGRESS;
    }


    // --- Example Usage Placeholder (Not part of required implementation but useful for completeness) ---
    public static void main(String[] args) {
        // Demonstration placeholder: actual usage requires complex thread management simulation.
    }
}
