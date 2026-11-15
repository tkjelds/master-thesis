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

    private static final long MAX_COUNTER = 9223372036854775807L;

    private final Object lock = new Object();
    private final int size;

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
        synchronized (lock) {
            board = new char[size][size];
            clearBoard();
            currentPlayer = 'X';
            winner = '.';
            draw = false;
            moveCount = 0;
            generation = 0;
            revision = 0;
        }
    }

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
            if (revision == MAX_COUNTER) {
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
            if (revision == MAX_COUNTER || generation == MAX_COUNTER) {
                throw new IllegalStateException("Version counter overflow");
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
        synchronized (lock) {
            return copySnapshot();
        }
    }

    private MoveResult result(Status status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private void clearBoard() {
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                board[row][column] = '.';
            }
        }
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

        return new Snapshot(size, copy, currentPlayer, winner, draw, moveCount,
                            generation, revision, version);
    }

    private boolean hasFive(int row, int column, char player) {
        return lineHasFive(row, column, player, 0, 1)
                || lineHasFive(row, column, player, 1, 0)
                || lineHasFive(row, column, player, 1, 1)
                || lineHasFive(row, column, player, 1, -1);
    }

    private boolean lineHasFive(int row, int column, char player, int dr, int dc) {
        int count = 1;
        count += countDirection(row, column, player, dr, dc);
        if (count >= 5) {
            return true;
        }
        count += countDirection(row, column, player, -dr, -dc);
        return count >= 5;
    }

    private int countDirection(int row, int column, char player, int dr, int dc) {
        int count = 0;
        int r = row + dr;
        int c = column + dc;
        while (r >= 0 && r < size && c >= 0 && c < size
                && board[r][c] == player && count < 4) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }
}
