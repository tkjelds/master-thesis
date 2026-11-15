@GomokuVersionState.ThreadSafe
public final class GomokuVersionState {
    @interface ThreadSafe {}

    public static final char EMPTY = '.';
    public static final char X = 'X';
    public static final char O = 'O';

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
            this.board = copyBoard(board);
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.moveCount = moveCount;
            this.generation = generation;
            this.revision = revision;
            this.version = version;
        }

        public char[][] getBoard() {
            return copyBoard(board);
        }
    }

    private final Object lock = new Object();
    private final int size;
    private final long cellCount;

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
        this.cellCount = (long) size * size;
        this.board = newEmptyBoard(size);
        this.currentPlayer = X;
        this.winner = EMPTY;
    }

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != X && player != O) {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates are outside the board");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return result(Status.STALE_REVISION);
            }
            if (winner != EMPTY || draw) {
                return result(Status.FINISHED);
            }
            if (player != currentPlayer) {
                return result(Status.WRONG_TURN);
            }
            if (board[row][column] != EMPTY) {
                return result(Status.OCCUPIED);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }

            board[row][column] = player;
            moveCount++;

            if (hasFive(row, column, player)) {
                winner = player;
            } else if (moveCount == cellCount) {
                draw = true;
            } else {
                currentPlayer = player == X ? O : X;
            }

            revision++;
            return result(Status.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }
            if (generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation overflow");
            }

            char[][] emptyBoard = newEmptyBoard(size);
            board = emptyBoard;
            currentPlayer = X;
            winner = EMPTY;
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
        if (winner != EMPTY) {
            version += ".WIN_" + winner;
        } else if (draw) {
            version += ".DRAW";
        }

        return new Snapshot(
                size, board, currentPlayer, winner, draw,
                moveCount, generation, revision, version);
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
                result[row][column] = EMPTY;
            }
        }
        return result;
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
