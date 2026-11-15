@GomokuVersionState.ThreadSafe
public final class GomokuVersionState {
    @interface ThreadSafe {}

    public enum MoveStatus {
        MOVED,
        STALE_REVISION,
        FINISHED,
        WRONG_TURN,
        OCCUPIED
    }

    public static final class MoveResult {
        public final MoveStatus status;
        public final long revision;
        public final long generation;
        public final char winner;
        public final boolean draw;

        private MoveResult(MoveStatus status, long revision, long generation,
                           char winner, boolean draw) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.draw = draw;
        }
    }

    public static final class Snapshot {
        public final char[][] board;
        public final char currentPlayer;
        public final long moveCount;
        public final char winner;
        public final boolean draw;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(char[][] board, char currentPlayer, long moveCount,
                         char winner, boolean draw, long generation,
                         long revision, String version) {
            this.board = copyBoard(board);
            this.currentPlayer = currentPlayer;
            this.moveCount = moveCount;
            this.winner = winner;
            this.draw = draw;
            this.generation = generation;
            this.revision = revision;
            this.version = version;
        }

        public char[][] getBoard() {
            return copyBoard(board);
        }
    }

    private final int size;
    private final Object lock = new Object();

    // All mutable state below is guarded by lock.
    private char[][] board;
    private char currentPlayer;
    private long moveCount;
    private char winner;
    private boolean draw;
    private long generation;
    private long revision;
    private String version;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }

        this.size = size;
        this.board = emptyBoard(size);
        this.currentPlayer = 'X';
        this.moveCount = 0L;
        this.winner = '.';
        this.draw = false;
        this.generation = 0L;
        this.revision = 0L;
        this.version = makeVersion(size, moveCount, currentPlayer, winner, draw);
    }

    public MoveResult makeMove(char player, int row, int column,
                               long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates are out of bounds");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Revision cannot be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return result(MoveStatus.STALE_REVISION);
            }
            if (winner != '.' || draw) {
                return result(MoveStatus.FINISHED);
            }
            if (player != currentPlayer) {
                return result(MoveStatus.WRONG_TURN);
            }
            if (board[row][column] != '.') {
                return result(MoveStatus.OCCUPIED);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision counter overflow");
            }

            board[row][column] = player;
            moveCount++;

            if (hasFiveInARow(row, column, player)) {
                winner = player;
            } else if (moveCount == (long) size * size) {
                draw = true;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
            }

            revision++;
            version = makeVersion(size, moveCount, currentPlayer, winner, draw);
            return result(MoveStatus.MOVED);
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE || generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision or generation counter overflow");
            }

            char[][] newBoard = emptyBoard(size);
            long newGeneration = generation + 1L;
            long newRevision = revision + 1L;
            String newVersion = makeVersion(size, 0L, 'X', '.', false);

            board = newBoard;
            currentPlayer = 'X';
            moveCount = 0L;
            winner = '.';
            draw = false;
            generation = newGeneration;
            revision = newRevision;
            version = newVersion;

            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private MoveResult result(MoveStatus status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(board, currentPlayer, moveCount, winner, draw,
                            generation, revision, version);
    }

    private boolean hasFiveInARow(int row, int column, char player) {
        return countInDirection(row, column, player, 0, 1)
                    + countInDirection(row, column, player, 0, -1) - 1 >= 5
                || countInDirection(row, column, player, 1, 0)
                    + countInDirection(row, column, player, -1, 0) - 1 >= 5
                || countInDirection(row, column, player, 1, 1)
                    + countInDirection(row, column, player, -1, -1) - 1 >= 5
                || countInDirection(row, column, player, 1, -1)
                    + countInDirection(row, column, player, -1, 1) - 1 >= 5;
    }

    private int countInDirection(int row, int column, char player,
                                 int rowStep, int columnStep) {
        int count = 0;
        int r = row;
        int c = column;

        while (r >= 0 && r < size && c >= 0 && c < size
                && board[r][c] == player) {
            count++;
            r += rowStep;
            c += columnStep;
        }
        return count;
    }

    private static char[][] emptyBoard(int size) {
        char[][] result = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                result[row][column] = '.';
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

    private static String makeVersion(int size, long moveCount,
                                     char currentPlayer, char winner,
                                     boolean draw) {
        String result = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            return result + ".WIN_X";
        }
        if (winner == 'O') {
            return result + ".WIN_O";
        }
        if (draw) {
            return result + ".DRAW";
        }
        return result;
    }
}
