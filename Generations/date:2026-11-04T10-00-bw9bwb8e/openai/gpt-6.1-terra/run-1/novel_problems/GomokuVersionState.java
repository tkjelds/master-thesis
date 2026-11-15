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

        private MoveResult(Status status, long revision, long generation, char winner) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
        }
    }

    public static final class Snapshot {
        public final int size;
        // This board is detached from the state and may be modified by the caller.
        public final char[][] board;
        public final char currentPlayer;
        public final char winner;
        public final boolean draw;
        public final long moveCount;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(int size, char[][] board, char currentPlayer, char winner,
                         boolean draw, long moveCount, long generation,
                         long revision, String version) {
            this.size = size;
            this.board = board;
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.moveCount = moveCount;
            this.generation = generation;
            this.revision = revision;
            this.version = version;
        }
    }

    private final Object lock = new Object();
    private final int size;
    private final long capacity;

    private char[][] board;
    private char currentPlayer;
    private char winner;
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
        this.board = newEmptyBoard(size);
        this.currentPlayer = 'X';
        this.winner = '.';
        this.draw = false;
        this.moveCount = 0;
        this.generation = 0;
        this.revision = 0;
    }

    // Operations wait only for the state monitor; there are no condition waits.
    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Invalid player");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates out of bounds");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative revision");
        }

        synchronized (lock) {
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
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }

            board[row][column] = player;
            moveCount++;

            if (hasFive(row, column, player)) {
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

    public Snapshot reset() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE || generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Version overflow");
            }

            char[][] emptyBoard = newEmptyBoard(size);
            board = emptyBoard;
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
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private MoveResult result(Status status) {
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

    private boolean hasFive(int row, int column, char player) {
        return hasFiveInDirection(row, column, player, 0, 1)
            || hasFiveInDirection(row, column, player, 1, 0)
            || hasFiveInDirection(row, column, player, 1, 1)
            || hasFiveInDirection(row, column, player, 1, -1);
    }

    private boolean hasFiveInDirection(int row, int column, char player,
                                       int rowStep, int columnStep) {
        int count = 1;

        for (int direction = -1; direction <= 1; direction += 2) {
            int r = row + direction * rowStep;
            int c = column + direction * columnStep;
            while (r >= 0 && r < size && c >= 0 && c < size
                    && board[r][c] == player) {
                count++;
                if (count >= 5) {
                    return true;
                }
                r += direction * rowStep;
                c += direction * columnStep;
            }
        }
        return false;
    }

    private static char[][] newEmptyBoard(int size) {
        char[][] result = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                result[row][column] = '.';
            }
        }
        return result;
    }
}
