@GomokuVersionState.ThreadSafe
public final class GomokuVersionState {
    @interface ThreadSafe {}


    public enum Status {
        OK,
        STALE_REVISION,
        FINISHED,
        WRONG_TURN,
        OCCUPIED
    }

    private static final char EMPTY = '.';
    private static final char PLAYER_X = 'X';
    private static final char PLAYER_O = 'O';
    private static final char NO_WINNER = '\0';

    private static final int[][] DIRECTIONS = {
            {0, 1},   // horizontal
            {1, 0},   // vertical
            {1, 1},   // diagonal
            {1, -1}   // anti-diagonal
    };

    // All mutable state is guarded by 'lock'. Operations never block on
    // conditions: they either complete immediately or return a failure status.
    private final Object lock = new Object();

    private final int size;
    private final char[][] board;
    private char currentPlayer;
    private int moveCount;
    private char winner;
    private boolean draw;
    private long revision;
    private long generation;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("board size must be >= 5");
        }
        this.size = size;
        this.board = new char[size][size];
        synchronized (lock) {
            clearBoardLocked();
            this.currentPlayer = PLAYER_X;
            this.moveCount = 0;
            this.winner = NO_WINNER;
            this.draw = false;
            this.revision = 0L;
            this.generation = 0L;
        }
    }

    /**
     * Attempts a move. Never blocks; returns a status describing the outcome.
     * Invalid arguments throw IllegalArgumentException without mutation.
     * Failed moves do not consume revisions.
     */
    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        validatePlayer(player);
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("coordinates out of bounds");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return resultLocked(Status.STALE_REVISION);
            }
            if (isFinishedLocked()) {
                return resultLocked(Status.FINISHED);
            }
            if (player != currentPlayer) {
                return resultLocked(Status.WRONG_TURN);
            }
            if (board[row][column] != EMPTY) {
                return resultLocked(Status.OCCUPIED);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            // Mutation begins only after all checks have passed.
            board[row][column] = player;
            moveCount++;
            revision++;

            if (hasLineLocked(row, column, player)) {
                winner = player;
            } else if ((long) moveCount == (long) size * size) {
                draw = true;
            } else {
                currentPlayer = (player == PLAYER_X) ? PLAYER_O : PLAYER_X;
            }

            return resultLocked(Status.OK);
        }
    }

    /**
     * Clears the board and restores the starting state. Advances both
     * generation and revision once. Returns a deep snapshot of the new state.
     */
    public Snapshot reset() {
        synchronized (lock) {
            if (generation == Long.MAX_VALUE || revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision or generation overflow");
            }
            clearBoardLocked();
            currentPlayer = PLAYER_X;
            moveCount = 0;
            winner = NO_WINNER;
            draw = false;
            generation++;
            revision++;
            return snapshotLocked();
        }
    }

    /**
     * Returns a deep-copied, internally consistent view of all state.
     */
    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    // ----- helpers; caller must hold 'lock' -----

    private void clearBoardLocked() {
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                board[r][c] = EMPTY;
            }
        }
    }

    private boolean isFinishedLocked() {
        return winner != NO_WINNER || draw;
    }

    private boolean hasLineLocked(int row, int column, char player) {
        for (int[] d : DIRECTIONS) {
            int count = 1;
            count += countDirectionLocked(row, column, d[0], d[1], player);
            count += countDirectionLocked(row, column, -d[0], -d[1], player);
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    private int countDirectionLocked(int row, int column, int dr, int dc, char player) {
        int count = 0;
        int r = row + dr;
        int c = column + dc;
        while (r >= 0 && r < size && c >= 0 && c < size && board[r][c] == player) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }

    private MoveResult resultLocked(Status status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(board, size, currentPlayer, moveCount, winner, draw,
                revision, generation);
    }

    private static void validatePlayer(char player) {
        if (player != PLAYER_X && player != PLAYER_O) {
            throw new IllegalArgumentException("player must be 'X' or 'O'");
        }
    }

    // ----- detached result types -----

    public static final class MoveResult {
        private final Status status;
        private final long revision;
        private final long generation;
        private final char winner;
        private final boolean draw;

        MoveResult(Status status, long revision, long generation, char winner, boolean draw) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.draw = draw;
        }

        public Status getStatus() {
            return status;
        }

        public long getRevision() {
            return revision;
        }

        public long getGeneration() {
            return generation;
        }

        /** Returns 'X', 'O', or '\0' if there is no winner. */
        public char getWinner() {
            return winner;
        }

        public boolean isDraw() {
            return draw;
        }
    }

    public static final class Snapshot {
        private final char[][] board;
        private final int size;
        private final char currentPlayer;
        private final int moveCount;
        private final char winner;
        private final boolean draw;
        private final long revision;
        private final long generation;
        private final String version;

        Snapshot(char[][] sourceBoard, int size, char currentPlayer, int moveCount,
                 char winner, boolean draw, long revision, long generation) {
            this.size = size;
            this.board = new char[size][size];
            for (int r = 0; r < size; r++) {
                System.arraycopy(sourceBoard[r], 0, this.board[r], 0, size);
            }
            this.currentPlayer = currentPlayer;
            this.moveCount = moveCount;
            this.winner = winner;
            this.draw = draw;
            this.revision = revision;
            this.generation = generation;

            String v = size + "." + moveCount + "." + (int) currentPlayer;
            if (winner == PLAYER_X) {
                v += ".WIN_X";
            } else if (winner == PLAYER_O) {
                v += ".WIN_O";
            } else if (draw) {
                v += ".DRAW";
            }
            this.version = v;
        }

        public int getSize() {
            return size;
        }

        /** Returns a deep copy of the board. */
        public char[][] getBoard() {
            char[][] copy = new char[size][size];
            for (int r = 0; r < size; r++) {
                System.arraycopy(board[r], 0, copy[r], 0, size);
            }
            return copy;
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }

        public int getMoveCount() {
            return moveCount;
        }

        /** Returns 'X', 'O', or '\0' if there is no winner. */
        public char getWinner() {
            return winner;
        }

        public boolean isDraw() {
            return draw;
        }

        public boolean isFinished() {
            return winner != NO_WINNER || draw;
        }

        public long getRevision() {
            return revision;
        }

        public long getGeneration() {
            return generation;
        }

        /** Display metadata only; not a concurrency token. */
        public String getVersion() {
            return version;
        }
    }

    private static final char EMPTY_CELL_MARKER = EMPTY;
}
