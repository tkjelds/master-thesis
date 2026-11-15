@GomokuVersionState.ThreadSafe
public final class GomokuVersionState {
    @interface ThreadSafe {}

    public enum Status {
        SUCCESS,
        STALE_REVISION,
        FINISHED,
        WRONG_TURN,
        OCCUPIED
    }

    public static final class MoveResult {
        public final Status status;
        public final long revision;
        public final long generation;
        /** '.' when there is no winner. */
        public final char winner;

        private MoveResult(Status status, long revision,
                           long generation, char winner) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
        }
    }

    public static final class Snapshot {
        /** Detached from the game and from every other snapshot. */
        public final char[][] board;
        public final int size;
        public final char currentPlayer;
        /** '.' when there is no winner. */
        public final char winner;
        public final boolean draw;
        public final boolean finished;
        public final long moveCount;
        public final long revision;
        public final long generation;
        public final String version;

        private Snapshot(char[][] board, int size, char currentPlayer,
                         char winner, boolean draw, long moveCount,
                         long revision, long generation, String version) {
            this.board = board;
            this.size = size;
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.finished = winner != '.' || draw;
            this.moveCount = moveCount;
            this.revision = revision;
            this.generation = generation;
            this.version = version;
        }
    }

    private static final long MAX_COUNTER = 9223372036854775807L;

    private final Object monitor = new Object();
    private final int size;
    private final char[][] board;

    private char currentPlayer = 'X';
    private char winner = '.';
    private boolean draw;
    private long moveCount;
    private long revision;
    private long generation;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }
        this.size = size;
        this.board = new char[size][size];
        clearBoard();
    }

    /**
     * Waits only to acquire the monitor; rule failures return immediately
     * after acquisition. Counter overflow throws before any mutation.
     */
    public MoveResult makeMove(char player, int row, int column,
                               long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates are out of bounds");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Revision must be nonnegative");
        }

        synchronized (monitor) {
            if (expectedRevision != revision) {
                return result(Status.STALE_REVISION);
            }
            if (winner != '.' || draw) {
                return result(Status.FINISHED);
            }
            if (player != currentPlayer) {
                return result(Status.WRONG_TURN);
            }
            if (board[row][column] != '.') {
                return result(Status.OCCUPIED);
            }
            if (revision == MAX_COUNTER) {
                throw new ArithmeticException("Revision overflow");
            }

            board[row][column] = player;
            moveCount++;

            if (hasFive(player, row, column)) {
                winner = player;
            } else if (moveCount == (long) size * size) {
                draw = true;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
            }

            revision++;
            return result(Status.SUCCESS);
        }
    }

    /**
     * Waits only to acquire the monitor. Overflow leaves the state unchanged.
     */
    public Snapshot reset() {
        synchronized (monitor) {
            if (revision == MAX_COUNTER) {
                throw new ArithmeticException("Revision overflow");
            }
            if (generation == MAX_COUNTER) {
                throw new ArithmeticException("Generation overflow");
            }

            clearBoard();
            currentPlayer = 'X';
            winner = '.';
            draw = false;
            moveCount = 0;
            generation++;
            revision++;

            return copySnapshot();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return copySnapshot();
        }
    }

    private void clearBoard() {
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                board[row][column] = '.';
            }
        }
    }

    private MoveResult result(Status status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private Snapshot copySnapshot() {
        char[][] copy = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                copy[row][column] = board[row][column];
            }
        }

        String version = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            version += ".WIN_X";
        } else if (winner == 'O') {
            version += ".WIN_O";
        } else if (draw) {
            version += ".DRAW";
        }

        return new Snapshot(copy, size, currentPlayer, winner, draw,
                            moveCount, revision, generation, version);
    }

    private boolean hasFive(char player, int row, int column) {
        return lineLength(player, row, column, 0, 1) >= 5
                || lineLength(player, row, column, 1, 0) >= 5
                || lineLength(player, row, column, 1, 1) >= 5
                || lineLength(player, row, column, 1, -1) >= 5;
    }

    private int lineLength(char player, int row, int column,
                           int rowStep, int columnStep) {
        return 1
                + countDirection(player, row, column, rowStep, columnStep)
                + countDirection(player, row, column, -rowStep, -columnStep);
    }

    private int countDirection(char player, int row, int column,
                               int rowStep, int columnStep) {
        int count = 0;
        // Four neighbors per side suffice to detect five or more stones.
        while (count < 4) {
            row += rowStep;
            column += columnStep;
            if (row < 0 || row >= size || column < 0 || column >= size
                    || board[row][column] != player) {
                break;
            }
            count++;
        }
        return count;
    }
}
