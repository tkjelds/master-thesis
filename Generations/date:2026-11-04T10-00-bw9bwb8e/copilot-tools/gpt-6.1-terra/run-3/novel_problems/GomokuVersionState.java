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
        public final char currentPlayer;
        public final char winner;
        public final boolean draw;
        public final long moveCount;
        public final long generation;
        public final long revision;
        public final String version;

        private final char[][] board;

        private Snapshot(
                int size,
                char[][] board,
                char currentPlayer,
                char winner,
                boolean draw,
                long moveCount,
                long generation,
                long revision,
                String version) {
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

        public char[][] getBoard() {
            return copyBoard(board, size);
        }
    }

    private final Object lock = new Object();
    private final int size;
    private final char[][] board;

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
        this.board = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                board[row][column] = '.';
            }
        }
    }

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates are out of bounds");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
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
            } else if (moveCount == (long) size * size) {
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
                throw new IllegalStateException("Revision or generation overflow");
            }

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
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private MoveResult result(Status status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private Snapshot snapshotLocked() {
        String version = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            version += ".WIN_X";
        } else if (winner == 'O') {
            version += ".WIN_O";
        } else if (draw) {
            version += ".DRAW";
        }

        return new Snapshot(
                size,
                copyBoard(board, size),
                currentPlayer,
                winner,
                draw,
                moveCount,
                generation,
                revision,
                version);
    }

    private static char[][] copyBoard(char[][] source, int size) {
        char[][] copy = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                copy[row][column] = source[row][column];
            }
        }
        return copy;
    }

    private boolean hasFive(int row, int column, char player) {
        return hasFiveInDirection(row, column, player, 0, 1)
                || hasFiveInDirection(row, column, player, 1, 0)
                || hasFiveInDirection(row, column, player, 1, 1)
                || hasFiveInDirection(row, column, player, 1, -1);
    }

    private boolean hasFiveInDirection(
            int row, int column, char player, int rowStep, int columnStep) {
        int count = 1;

        int r = row + rowStep;
        int c = column + columnStep;
        while (r >= 0 && r < size && c >= 0 && c < size && board[r][c] == player) {
            if (++count >= 5) {
                return true;
            }
            r += rowStep;
            c += columnStep;
        }

        r = row - rowStep;
        c = column - columnStep;
        while (r >= 0 && r < size && c >= 0 && c < size && board[r][c] == player) {
            if (++count >= 5) {
                return true;
            }
            r -= rowStep;
            c -= columnStep;
        }

        return false;
    }
}
