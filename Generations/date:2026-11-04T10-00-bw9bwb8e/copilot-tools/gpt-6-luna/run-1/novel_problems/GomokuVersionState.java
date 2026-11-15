@GomokuVersionState.ThreadSafe
public final class GomokuVersionState {
    @interface ThreadSafe {}

    public enum Status {
        APPLIED,
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

        private MoveResult(Status status, long revision, long generation,
                           char winner) {
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

        private Snapshot(char[][] board, char currentPlayer, char winner,
                         boolean draw, long moveCount, long generation,
                         long revision, String version) {
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

    private final Object monitor = new Object();
    private final int size;
    private final long capacity;
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
        this.capacity = (long) size * size;
        this.board = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                board[row][column] = '.';
            }
        }
        this.currentPlayer = 'X';
        this.winner = '\0';
        this.draw = false;
        this.moveCount = 0L;
        this.generation = 0L;
        this.revision = 0L;
        this.version = makeVersion();
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

        synchronized (monitor) {
            if (expectedRevision != revision) {
                return moveResult(Status.STALE_REVISION);
            }
            if (winner != '\0' || draw) {
                return moveResult(Status.FINISHED);
            }
            if (player != currentPlayer) {
                return moveResult(Status.WRONG_TURN);
            }
            if (board[row][column] != '.') {
                return moveResult(Status.OCCUPIED);
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision overflow");
            }

            board[row][column] = player;
            moveCount++;
            revision++;

            if (hasFive(row, column, player)) {
                winner = player;
            } else if (moveCount == capacity) {
                draw = true;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
            }
            version = makeVersion();
            return moveResult(Status.APPLIED);
        }
    }

    public Snapshot reset() {
        synchronized (monitor) {
            if (generation == Long.MAX_VALUE || revision == Long.MAX_VALUE) {
                throw new ArithmeticException("Generation or revision overflow");
            }

            char[][] emptyBoard = new char[size][size];
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    emptyBoard[row][column] = '.';
                }
            }

            board = emptyBoard;
            currentPlayer = 'X';
            winner = '\0';
            draw = false;
            moveCount = 0L;
            generation++;
            revision++;
            version = makeVersion();
            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return snapshotLocked();
        }
    }

    private boolean hasFive(int row, int column, char player) {
        return lineLength(row, column, 0, 1, player) >= 5
                || lineLength(row, column, 1, 0, player) >= 5
                || lineLength(row, column, 1, 1, player) >= 5
                || lineLength(row, column, 1, -1, player) >= 5;
    }

    private int lineLength(int row, int column, int rowStep, int columnStep,
                           char player) {
        int length = 1;
        length += countDirection(row, column, rowStep, columnStep, player);
        length += countDirection(row, column, -rowStep, -columnStep, player);
        return length;
    }

    private int countDirection(int row, int column, int rowStep, int columnStep,
                               char player) {
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

    private MoveResult moveResult(Status status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private Snapshot snapshotLocked() {
        char[][] copiedBoard = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                copiedBoard[row][column] = board[row][column];
            }
        }
        return new Snapshot(copiedBoard, currentPlayer, winner, draw, moveCount,
                generation, revision, version);
    }

    private String makeVersion() {
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
