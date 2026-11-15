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
        public final boolean draw;

        private MoveResult(Status status, long revision, long generation,
                           char winner, boolean draw) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.draw = draw;
        }

        public Status getStatus() {
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

    private final int size;
    private final Object lock = new Object();
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
        synchronized (lock) {
            board = emptyBoard(size);
            currentPlayer = 'X';
            winner = '\0';
            draw = false;
            moveCount = 0L;
            generation = 0L;
            revision = 0L;
            version = createVersion(size, moveCount, currentPlayer, winner, draw);
        }
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
                return result(Status.STALE_REVISION);
            }
            if (winner != '\0' || draw) {
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
            revision++;
            if (hasFiveInARow(row, column, player)) {
                winner = player;
            } else if (moveCount == (long) size * size) {
                draw = true;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
            }
            version = createVersion(size, moveCount, currentPlayer, winner, draw);
            return result(Status.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }

            board = emptyBoard(size);
            currentPlayer = 'X';
            winner = '\0';
            draw = false;
            moveCount = 0L;
            generation++;
            revision++;
            version = createVersion(size, moveCount, currentPlayer, winner, draw);
            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private MoveResult result(Status status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(copyBoard(board), currentPlayer, winner, draw,
                moveCount, generation, revision, version);
    }

    private boolean hasFiveInARow(int row, int column, char player) {
        return countInDirection(row, column, 0, 1, player) >= 5
                || countInDirection(row, column, 1, 0, player) >= 5
                || countInDirection(row, column, 1, 1, player) >= 5
                || countInDirection(row, column, 1, -1, player) >= 5;
    }

    private int countInDirection(int row, int column, int rowStep,
                                 int columnStep, char player) {
        int count = 1;
        count += countAlong(row, column, rowStep, columnStep, player);
        count += countAlong(row, column, -rowStep, -columnStep, player);
        return count;
    }

    private int countAlong(int row, int column, int rowStep,
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

    private static String createVersion(int size, long moveCount,
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
