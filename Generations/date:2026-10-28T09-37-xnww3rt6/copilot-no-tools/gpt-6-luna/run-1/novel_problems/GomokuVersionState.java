@GomokuVersionState.ThreadSafe
public final class GomokuVersionState {
    @interface ThreadSafe {}

    public enum MoveStatus {
        SUCCESS,
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

        private MoveResult(MoveStatus status, long revision, long generation, char winner) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
        }
    }

    public static final class Snapshot {
        public final char[][] board;
        public final char currentPlayer;
        public final char winner;
        public final boolean draw;
        public final long moveCount;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(char[][] board, char currentPlayer, char winner, boolean draw,
                         long moveCount, long generation, long revision, String version) {
            this.board = copyBoard(board);
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

    private char[][] board;
    private char currentPlayer;
    private char winner;
    private boolean draw;
    private long moveCount;
    private long generation;
    private long revision;
    private String version;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }

        this.size = size;
        this.board = newEmptyBoard(size);
        this.currentPlayer = 'X';
        this.winner = '.';
        this.draw = false;
        this.moveCount = 0L;
        this.generation = 0L;
        this.revision = 0L;
        this.version = makeVersion(0L, 'X', '.');
    }

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates are out of bounds");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
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
                throw new IllegalStateException("Revision overflow");
            }

            long nextMoveCount = moveCount + 1L;
            char nextWinner = hasFiveAt(row, column, player) ? player : '.';
            boolean nextDraw = nextWinner == '.' && nextMoveCount == (long) size * size;
            char nextPlayer = (nextWinner != '.' || nextDraw)
                    ? currentPlayer
                    : (currentPlayer == 'X' ? 'O' : 'X');
            String nextVersion = makeVersion(
                    nextMoveCount, nextPlayer, nextWinner, nextDraw);

            board[row][column] = player;
            moveCount = nextMoveCount;
            winner = nextWinner;
            draw = nextDraw;
            currentPlayer = nextPlayer;
            revision++;
            version = nextVersion;

            return result(MoveStatus.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (generation == Long.MAX_VALUE || revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation or revision overflow");
            }

            char[][] emptyBoard = newEmptyBoard(size);
            String emptyVersion = makeVersion(0L, 'X', '.');

            board = emptyBoard;
            currentPlayer = 'X';
            winner = '.';
            draw = false;
            moveCount = 0L;
            generation++;
            revision++;
            version = emptyVersion;

            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private MoveResult result(MoveStatus status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                board, currentPlayer, winner, draw,
                moveCount, generation, revision, version);
    }

    private boolean hasFiveAt(int row, int column, char player) {
        return contiguousCount(row, column, player, 0, 1) >= 5
                || contiguousCount(row, column, player, 1, 0) >= 5
                || contiguousCount(row, column, player, 1, 1) >= 5
                || contiguousCount(row, column, player, 1, -1) >= 5;
    }

    private int contiguousCount(int row, int column, char player, int rowStep, int columnStep) {
        int count = 1;
        count += countDirection(row, column, player, rowStep, columnStep);
        count += countDirection(row, column, player, -rowStep, -columnStep);
        return count;
    }

    private int countDirection(int row, int column, char player, int rowStep, int columnStep) {
        int count = 0;
        int nextRow = row + rowStep;
        int nextColumn = column + columnStep;

        while (nextRow >= 0 && nextRow < size
                && nextColumn >= 0 && nextColumn < size
                && board[nextRow][nextColumn] == player) {
            count++;
            nextRow += rowStep;
            nextColumn += columnStep;
        }

        return count;
    }

    private String makeVersion(long count, char player, char winningPlayer) {
        return makeVersion(count, player, winningPlayer, false);
    }

    private String makeVersion(long count, char player, char winningPlayer, boolean isDraw) {
        String value = size + "." + count + "." + (int) player;
        if (winningPlayer != '.') {
            value += winningPlayer == 'X' ? ".WIN_X" : ".WIN_O";
        } else if (isDraw) {
            value += ".DRAW";
        }
        return value;
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
