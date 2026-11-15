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

    public static final class MoveResult {
        private final Status status;
        private final long revision;
        private final long generation;
        private final char winner;

        private MoveResult(Status status, long revision, long generation, char winner) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
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

        /** Returns 'X', 'O', or '.' when there is no winner. */
        public char getWinner() {
            return winner;
        }
    }

    public static final class Snapshot {
        private final int size;
        private final char[][] board;
        private final char currentPlayer;
        private final char winner;
        private final boolean draw;
        private final int moveCount;
        private final long revision;
        private final long generation;
        private final String version;

        private Snapshot(int size, char[][] board, char currentPlayer, char winner, boolean draw,
                         int moveCount, long revision, long generation, String version) {
            this.size = size;
            this.board = board;
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.moveCount = moveCount;
            this.revision = revision;
            this.generation = generation;
            this.version = version;
        }

        public int getSize() {
            return size;
        }

        /** Returns a deep copy of the board. */
        public char[][] getBoard() {
            char[][] copy = new char[size][];
            for (int r = 0; r < size; r++) {
                copy[r] = new char[size];
                System.arraycopy(board[r], 0, copy[r], 0, size);
            }
            return copy;
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }

        /** Returns 'X', 'O', or '.' when there is no winner. */
        public char getWinner() {
            return winner;
        }

        public boolean isDraw() {
            return draw;
        }

        public boolean isFinished() {
            return winner != EMPTY || draw;
        }

        public int getMoveCount() {
            return moveCount;
        }

        public long getRevision() {
            return revision;
        }

        public long getGeneration() {
            return generation;
        }

        public String getVersion() {
            return version;
        }
    }

    private static final char EMPTY = '.';
    private static final char PLAYER_X = 'X';
    private static final char PLAYER_O = 'O';

    private final int size;
    private final int cellCount;
    private final Object lock = new Object();

    // All mutable fields below are guarded by 'lock'.
    private final char[][] board;
    private char currentPlayer;
    private char winner;
    private int moveCount;
    private long revision;
    private long generation;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("board size must be >= 5");
        }
        this.size = size;
        this.cellCount = size * size;
        this.board = new char[size][size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                board[r][c] = EMPTY;
            }
        }
        this.currentPlayer = PLAYER_X;
        this.winner = EMPTY;
        this.moveCount = 0;
        this.revision = 0L;
        this.generation = 0L;
    }

    /**
     * Attempts a move. Never blocks: returns a status immediately. Failed moves
     * do not mutate state or consume a revision.
     */
    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != PLAYER_X && player != PLAYER_O) {
            throw new IllegalArgumentException("invalid player: " + player);
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("coordinates out of bounds: " + row + "," + column);
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("negative revision: " + expectedRevision);
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MoveResult(Status.STALE_REVISION, revision, generation, winner);
            }
            if (winner != EMPTY || moveCount == cellCount) {
                return new MoveResult(Status.FINISHED, revision, generation, winner);
            }
            if (player != currentPlayer) {
                return new MoveResult(Status.WRONG_TURN, revision, generation, winner);
            }
            if (board[row][column] != EMPTY) {
                return new MoveResult(Status.OCCUPIED, revision, generation, winner);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            board[row][column] = player;
            moveCount++;

            if (isWinningMove(row, column, player)) {
                winner = player;
            } else if (moveCount < cellCount) {
                currentPlayer = (player == PLAYER_X) ? PLAYER_O : PLAYER_X;
            }
            // else: full board without winner is a draw; currentPlayer is not advanced.

            revision++;
            return new MoveResult(Status.OK, revision, generation, winner);
        }
    }

    /**
     * Clears the board and restores the starting state. Advances both generation
     * and revision once. Never resets the revision counter.
     */
    public Snapshot reset() {
        synchronized (lock) {
            if (generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }

            for (int r = 0; r < size; r++) {
                for (int c = 0; c < size; c++) {
                    board[r][c] = EMPTY;
                }
            }
            currentPlayer = PLAYER_X;
            winner = EMPTY;
            moveCount = 0;
            generation++;
            revision++;
            return buildSnapshotLocked();
        }
    }

    /** Returns a deep-copied, consistent view of all state. */
    public Snapshot snapshot() {
        synchronized (lock) {
            return buildSnapshotLocked();
        }
    }

    // Must be called while holding 'lock'.
    private Snapshot buildSnapshotLocked() {
        char[][] copy = new char[size][size];
        for (int r = 0; r < size; r++) {
            System.arraycopy(board[r], 0, copy[r], 0, size);
        }
        boolean draw = (winner == EMPTY) && moveCount == cellCount;

        StringBuilder sb = new StringBuilder();
        sb.append(size).append('.').append(moveCount).append('.').append((int) currentPlayer);
        if (winner == PLAYER_X) {
            sb.append(".WIN_X");
        } else if (winner == PLAYER_O) {
            sb.append(".WIN_O");
        } else if (draw) {
            sb.append(".DRAW");
        }

        return new Snapshot(size, copy, currentPlayer, winner, draw,
                moveCount, revision, generation, sb.toString());
    }

    // Must be called while holding 'lock'.
    private boolean isWinningMove(int row, int column, char player) {
        return countDirection(row, column, 0, 1, player) >= 5
                || countDirection(row, column, 1, 0, player) >= 5
                || countDirection(row, column, 1, 1, player) >= 5
                || countDirection(row, column, 1, -1, player) >= 5;
    }

    // Must be called while holding 'lock'. Counts contiguous stones through (row, column) along a line.
    private int countDirection(int row, int column, int dr, int dc, char player) {
        int count = 1;
        count += countFrom(row, column, dr, dc, player);
        count += countFrom(row, column, -dr, -dc, player);
        return count;
    }

    // Must be called while holding 'lock'.
    private int countFrom(int row, int column, int dr, int dc, char player) {
        int n = 0;
        int r = row + dr;
        int c = column + dc;
        while (r >= 0 && r < size && c >= 0 && c < size && board[r][c] == player) {
            n++;
            r += dr;
            c += dc;
        }
        return n;
    }
}
