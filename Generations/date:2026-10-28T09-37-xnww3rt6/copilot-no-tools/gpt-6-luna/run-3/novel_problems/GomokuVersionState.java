@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}

    public enum MoveStatus {
        MOVED,
        WON,
        DRAW,
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
                         boolean draw, long moveCount, long generation, long revision,
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

        public boolean isDraw() {
            return draw;
        }
    }

    private final Object lock = new Object();
    private final int size;
    private final char[][] board;
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
        this.board = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                board[row][column] = '.';
            }
        }
        currentPlayer = 'X';
        winner = '.';
        version = buildVersion();
    }

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Move is outside the board");
        }
        if (expectedRevision < 0) {
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
            revision++;

            MoveStatus status;
            if (hasFiveInARow(row, column, player)) {
                winner = player;
                status = MoveStatus.WON;
            } else if (moveCount == (long) size * size) {
                draw = true;
                status = MoveStatus.DRAW;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
                status = MoveStatus.MOVED;
            }

            version = buildVersion();
            return result(status);
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision counter overflow");
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
            version = buildVersion();
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
        return new Snapshot(size, board, currentPlayer, winner, draw, moveCount,
                            generation, revision, version);
    }

    private boolean hasFiveInARow(int row, int column, char player) {
        return countInDirection(row, column, player, 0, 1) >= 5
                || countInDirection(row, column, player, 1, 0) >= 5
                || countInDirection(row, column, player, 1, 1) >= 5
                || countInDirection(row, column, player, 1, -1) >= 5;
    }

    private int countInDirection(int row, int column, char player, int rowStep, int columnStep) {
        return 1
                + countOneWay(row, column, player, rowStep, columnStep)
                + countOneWay(row, column, player, -rowStep, -columnStep);
    }

    private int countOneWay(int row, int column, char player, int rowStep, int columnStep) {
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

    private String buildVersion() {
        String value = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            return value + ".WIN_X";
        }
        if (winner == 'O') {
            return value + ".WIN_O";
        }
        if (draw) {
            return value + ".DRAW";
        }
        return value;
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
