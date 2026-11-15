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
        public final int size;
        public final char[][] board;
        public final char currentPlayer;
        public final char winner;
        public final boolean draw;
        public final boolean finished;
        public final long moveCount;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(int size, char[][] board, char currentPlayer,
                         char winner, boolean draw, long moveCount,
                         long generation, long revision, String version) {
            this.size = size;
            this.board = board;
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.finished = winner != '.' || draw;
            this.moveCount = moveCount;
            this.generation = generation;
            this.revision = revision;
            this.version = version;
        }
    }

    private final Object monitor = new Object();
    private final int size;
    private final long capacity;
    private final char[][] board;

    private char currentPlayer = 'X';
    private char winner = '.'; // No winner.
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
        this.board = new char[size][size];

        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                board[row][column] = '.';
            }
        }
    }

    /*
     * Operations wait only to acquire the monitor. Game-state failures return
     * immediately; counter exhaustion throws before any state is changed.
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
                return resultLocked(Status.STALE_REVISION);
            }
            if (winner != '.' || draw) {
                return resultLocked(Status.FINISHED);
            }
            if (player != currentPlayer) {
                return resultLocked(Status.WRONG_TURN);
            }
            if (board[row][column] != '.') {
                return resultLocked(Status.OCCUPIED);
            }
            requireIncrementable(revision, "Revision overflow");

            board[row][column] = player;
            moveCount++;

            if (hasWinningLineLocked(player, row, column)) {
                winner = player;
            } else if (moveCount == capacity) {
                draw = true;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
            }

            revision++;
            return resultLocked(Status.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (monitor) {
            requireIncrementable(revision, "Revision overflow");
            requireIncrementable(generation, "Generation overflow");

            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    board[row][column] = '.';
                }
            }

            currentPlayer = 'X';
            winner = '.';
            draw = false;
            moveCount = 0;
            generation++;
            revision++;

            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return snapshotLocked();
        }
    }

    private static void requireIncrementable(long value, String message) {
        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException(message);
        }
    }

    private MoveResult resultLocked(Status status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private Snapshot snapshotLocked() {
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

        return new Snapshot(size, copy, currentPlayer, winner, draw,
                            moveCount, generation, revision, version);
    }

    private boolean hasWinningLineLocked(char player, int row, int column) {
        return lineLengthLocked(player, row, column, 0, 1) >= 5
                || lineLengthLocked(player, row, column, 1, 0) >= 5
                || lineLengthLocked(player, row, column, 1, 1) >= 5
                || lineLengthLocked(player, row, column, 1, -1) >= 5;
    }

    private int lineLengthLocked(char player, int row, int column,
                                 int rowStep, int columnStep) {
        return 1
                + countDirectionLocked(player, row, column,
                                       rowStep, columnStep)
                + countDirectionLocked(player, row, column,
                                       -rowStep, -columnStep);
    }

    private int countDirectionLocked(char player, int row, int column,
                                     int rowStep, int columnStep) {
        int count = 0;
        row += rowStep;
        column += columnStep;

        while (row >= 0 && row < size
                && column >= 0 && column < size
                && board[row][column] == player) {
            count++;
            row += rowStep;
            column += columnStep;
        }

        return count;
    }
}
