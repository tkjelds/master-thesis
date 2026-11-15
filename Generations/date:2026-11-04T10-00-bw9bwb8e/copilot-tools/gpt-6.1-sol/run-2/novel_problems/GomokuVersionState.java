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

        /** '.' means there is no winner. */
        public final char winner;

        public final boolean draw;

        private MoveResult(Status status, long revision, long generation,
                           char winner, boolean draw) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.draw = draw;
        }
    }

    public static final class Snapshot {
        public final int size;

        /** Detached from both the game and every other snapshot. */
        public final char[][] board;

        public final char currentPlayer;

        /** '.' means there is no winner. */
        public final char winner;

        public final boolean draw;
        public final boolean finished;
        public final long moveCount;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(int size, char[][] source, char currentPlayer,
                         char winner, boolean draw, long moveCount,
                         long generation, long revision) {
            this.size = size;
            this.board = copyBoard(source);
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.finished = winner != '.' || draw;
            this.moveCount = moveCount;
            this.generation = generation;
            this.revision = revision;

            String display = size + "." + moveCount + "." + (int) currentPlayer;
            if (winner == 'X') {
                display += ".WIN_X";
            } else if (winner == 'O') {
                display += ".WIN_O";
            } else if (draw) {
                display += ".DRAW";
            }
            this.version = display;
        }
    }

    private static final long MAX_COUNTER = 9223372036854775807L;

    private final Object monitor = new Object();
    private final int size;
    private final long capacity;

    // All mutable game state is guarded by monitor.
    private char[][] board;
    private char currentPlayer = 'X';
    private char winner = '.';
    private boolean draw;
    private long moveCount;
    private long generation;
    private long revision;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }
        this.size = size;
        this.capacity = (long) size * size;
        this.board = emptyBoard(size);
    }

    /**
     * Never waits for a turn or an available cell: failures return immediately
     * after acquiring the monitor. Revision exhaustion throws ArithmeticException
     * before any move mutation.
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
            } else if (moveCount == capacity) {
                draw = true;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
            }

            revision++;
            return result(Status.SUCCESS);
        }
    }

    /**
     * Clears the game without reusing a revision. Counter exhaustion throws
     * ArithmeticException before mutation.
     */
    public Snapshot reset() {
        synchronized (monitor) {
            if (revision == MAX_COUNTER) {
                throw new ArithmeticException("Revision overflow");
            }
            if (generation == MAX_COUNTER) {
                throw new ArithmeticException("Generation overflow");
            }

            char[][] clearedBoard = emptyBoard(size);
            board = clearedBoard;
            currentPlayer = 'X';
            winner = '.';
            draw = false;
            moveCount = 0;
            generation++;
            revision++;

            return snapshotUnderLock();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return snapshotUnderLock();
        }
    }

    private MoveResult result(Status status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private Snapshot snapshotUnderLock() {
        return new Snapshot(size, board, currentPlayer, winner, draw,
                            moveCount, generation, revision);
    }

    private boolean hasFive(char player, int row, int column) {
        return hasFiveAlong(player, row, column, 0, 1)
            || hasFiveAlong(player, row, column, 1, 0)
            || hasFiveAlong(player, row, column, 1, 1)
            || hasFiveAlong(player, row, column, 1, -1);
    }

    private boolean hasFiveAlong(char player, int row, int column,
                                 int rowStep, int columnStep) {
        return 1
            + countDirection(player, row, column, rowStep, columnStep)
            + countDirection(player, row, column, -rowStep, -columnStep)
            >= 5;
    }

    private int countDirection(char player, int row, int column,
                               int rowStep, int columnStep) {
        int count = 0;
        row += rowStep;
        column += columnStep;

        // Four neighbors per direction suffice to establish a winning line.
        while (count < 4
                && row >= 0 && row < size
                && column >= 0 && column < size
                && board[row][column] == player) {
            count++;
            row += rowStep;
            column += columnStep;
        }
        return count;
    }

    private static char[][] emptyBoard(int size) {
        char[][] cells = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                cells[row][column] = '.';
            }
        }
        return cells;
    }

    private static char[][] copyBoard(char[][] source) {
        char[][] copy = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            copy[row] = new char[source[row].length];
            for (int column = 0; column < source[row].length; column++) {
                copy[row][column] = source[row][column];
            }
        }
        return copy;
    }
}
