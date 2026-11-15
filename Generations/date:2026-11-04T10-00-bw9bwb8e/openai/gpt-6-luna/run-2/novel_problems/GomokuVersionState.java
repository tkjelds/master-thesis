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
        public final boolean draw;

        private MoveResult(MoveStatus status, long revision, long generation,
                           char winner, boolean draw) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.draw = draw;
        }

        public MoveStatus getStatus() {
            return status;
        }

        public long getRevision() {
            return revision;
        }

        public long getGeneration() {
            return generation;
        }

        public char getWinner() {
            return winner;
        }

        public boolean isDraw() {
            return draw;
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

        private Snapshot(char[][] sourceBoard, int size, char currentPlayer,
                         char winner, boolean draw, long moveCount,
                         long generation, long revision, String version) {
            this.size = size;
            this.board = copyBoard(sourceBoard);
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.moveCount = moveCount;
            this.generation = generation;
            this.revision = revision;
            this.version = version;
        }

        public int getSize() {
            return size;
        }

        public char[][] getBoard() {
            return copyBoard(board);
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }

        public char getWinner() {
            return winner;
        }

        public boolean isDraw() {
            return draw;
        }

        public long getMoveCount() {
            return moveCount;
        }

        public long getGeneration() {
            return generation;
        }

        public long getRevision() {
            return revision;
        }

        public String getVersion() {
            return version;
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
        this.board = emptyBoard(size);
        this.currentPlayer = 'X';
        this.winner = '.';
        this.draw = false;
        this.moveCount = 0L;
        this.generation = 0L;
        this.revision = 0L;
        this.version = makeVersion(0L, 'X', '.', false);
    }

    public MoveResult makeMove(char player, int row, int column,
                               long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Move is outside the board");
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
                throw new ArithmeticException("Revision overflow");
            }

            long nextMoveCount = moveCount + 1L;
            long nextRevision = revision + 1L;
            board[row][column] = player;

            char nextWinner = hasFive(row, column, player) ? player : '.';
            boolean nextDraw = nextWinner == '.' &&
                    nextMoveCount == (long) size * (long) size;
            char nextPlayer = (nextWinner != '.' || nextDraw)
                    ? currentPlayer
                    : (currentPlayer == 'X' ? 'O' : 'X');
            String nextVersion =
                    makeVersion(nextMoveCount, nextPlayer, nextWinner, nextDraw);

            moveCount = nextMoveCount;
            revision = nextRevision;
            winner = nextWinner;
            draw = nextDraw;
            currentPlayer = nextPlayer;
            version = nextVersion;

            return result(MoveStatus.SUCCESS);
        }
    }

    public Snapshot reset() {
        char[][] freshBoard = emptyBoard(size);

        synchronized (lock) {
            if (generation == Long.MAX_VALUE) {
                throw new ArithmeticException("Generation overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision overflow");
            }

            long nextGeneration = generation + 1L;
            long nextRevision = revision + 1L;
            String nextVersion = makeVersion(0L, 'X', '.', false);

            board = freshBoard;
            currentPlayer = 'X';
            winner = '.';
            draw = false;
            moveCount = 0L;
            generation = nextGeneration;
            revision = nextRevision;
            version = nextVersion;

            return snapshotUnderLock();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotUnderLock();
        }
    }

    private MoveResult result(MoveStatus status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private Snapshot snapshotUnderLock() {
        return new Snapshot(board, size, currentPlayer, winner, draw,
                moveCount, generation, revision, version);
    }

    private boolean hasFive(int row, int column, char player) {
        return contiguousCount(row, column, 0, 1, player) >= 5
                || contiguousCount(row, column, 1, 0, player) >= 5
                || contiguousCount(row, column, 1, 1, player) >= 5
                || contiguousCount(row, column, 1, -1, player) >= 5;
    }

    private int contiguousCount(int row, int column, int rowStep,
                                int columnStep, char player) {
        int count = 1;
        count += countDirection(row, column, rowStep, columnStep, player);
        count += countDirection(row, column, -rowStep, -columnStep, player);
        return count;
    }

    private int countDirection(int row, int column, int rowStep,
                               int columnStep, char player) {
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

    private String makeVersion(long count, char player, char winningPlayer,
                               boolean isDraw) {
        String value = size + "." + count + "." + (int) player;
        if (winningPlayer == 'X') {
            value += ".WIN_X";
        } else if (winningPlayer == 'O') {
            value += ".WIN_O";
        } else if (isDraw) {
            value += ".DRAW";
        }
        return value;
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
}
