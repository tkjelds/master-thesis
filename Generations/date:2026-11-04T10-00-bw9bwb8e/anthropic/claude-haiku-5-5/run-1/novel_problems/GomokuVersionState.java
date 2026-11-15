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
        private final int moveCount;
        private final char winner;
        private final boolean draw;
        private final String version;
        private final long revision;
        private final long generation;

        private Snapshot(int size, char[][] board, char currentPlayer, int moveCount,
                         char winner, boolean draw, String version,
                         long revision, long generation) {
            this.size = size;
            this.board = copyBoard(board);
            this.currentPlayer = currentPlayer;
            this.moveCount = moveCount;
            this.winner = winner;
            this.draw = draw;
            this.version = version;
            this.revision = revision;
            this.generation = generation;
        }

        public int getSize() {
            return size;
        }

        /** Returns a fresh deep copy of the board. */
        public char[][] getBoard() {
            return copyBoard(board);
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }

        public int getMoveCount() {
            return moveCount;
        }

        /** Returns 'X', 'O', or '.' when there is no winner. */
        public char getWinner() {
            return winner;
        }

        public boolean isDraw() {
            return draw;
        }

        public String getVersion() {
            return version;
        }

        public long getRevision() {
            return revision;
        }

        public long getGeneration() {
            return generation;
        }
    }

    private static final char EMPTY = '.';
    private static final char PLAYER_X = 'X';
    private static final char PLAYER_O = 'O';

    // Policy: all operations are non-blocking. Every state access is guarded by
    // the intrinsic monitor 'lock'. Operations that cannot succeed return a
    // Status code immediately; invalid arguments throw IllegalArgumentException,
    // and counter exhaustion throws IllegalStateException, both before mutation.
    private final Object lock = new Object();
    private final int size;
    private final char[][] board;
    private char currentPlayer = PLAYER_X;
    private int moveCount;
    private char winner = EMPTY;
    private long generation;
    private long revision;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("board size must be >= 5");
        }
        this.size = size;
        this.board = new char[size][size];
        clearBoardUnsafe();
    }

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
            if (isTerminal()) {
                return new MoveResult(Status.FINISHED, revision, generation, winner);
            }
            if (player != currentPlayer) {
                return new MoveResult(Status.WRONG_TURN, revision, generation, winner);
            }
            if (board[row][column] != EMPTY) {
                return new MoveResult(Status.OCCUPIED, revision, generation, winner);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision counter overflow");
            }

            // Mutation begins; all checks above have passed.
            board[row][column] = player;
            moveCount++;
            revision++;

            if (createsFiveOrMore(row, column, player)) {
                winner = player;
            } else if (moveCount == size * size) {
                // Full board with no winner: draw (winner remains EMPTY).
                // Current player is not advanced in terminal states.
            } else {
                currentPlayer = (player == PLAYER_X) ? PLAYER_O : PLAYER_X;
            }

            return new MoveResult(Status.OK, revision, generation, winner);
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation counter overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision counter overflow");
            }

            clearBoardUnsafe();
            currentPlayer = PLAYER_X;
            moveCount = 0;
            winner = EMPTY;
            generation++;
            revision++;

            return buildSnapshotUnsafe();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return buildSnapshotUnsafe();
        }
    }

    // ----- private helpers; callers must hold 'lock' -----

    private boolean isTerminal() {
        return winner != EMPTY || moveCount == size * size;
    }

    private void clearBoardUnsafe() {
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                board[r][c] = EMPTY;
            }
        }
    }

    private Snapshot buildSnapshotUnsafe() {
        boolean draw = winner == EMPTY && moveCount == size * size;
        StringBuilder sb = new StringBuilder();
        sb.append(size).append('.').append(moveCount).append('.').append((int) currentPlayer);
        if (winner == PLAYER_X) {
            sb.append(".WIN_X");
        } else if (winner == PLAYER_O) {
            sb.append(".WIN_O");
        } else if (draw) {
            sb.append(".DRAW");
        }
        return new Snapshot(size, board, currentPlayer, moveCount, winner, draw,
                sb.toString(), revision, generation);
    }

    private boolean createsFiveOrMore(int row, int column, char player) {
        int[][] directions = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int[] d : directions) {
            int count = 1 + countDirection(row, column, d[0], d[1], player)
                          + countDirection(row, column, -d[0], -d[1], player);
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    private int countDirection(int row, int column, int dr, int dc, char player) {
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

    private static char[][] copyBoard(char[][] source) {
        char[][] copy = new char[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
        return copy;
    }
}
