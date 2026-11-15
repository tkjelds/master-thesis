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
        public final int size;
        public final char currentPlayer;
        public final char winner;
        public final boolean draw;
        public final long moveCount;
        public final long generation;
        public final long revision;
        public final String versionString;
        private final char[][] board;

        private Snapshot(int size, char[][] board, char currentPlayer,
                         char winner, boolean draw, long moveCount,
                         long generation, long revision, String versionString) {
            this.size = size;
            this.board = copyBoard(board);
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.moveCount = moveCount;
            this.generation = generation;
            this.revision = revision;
            this.versionString = versionString;
        }

        public int getSize() {
            return size;
        }

        public char[][] getBoard() {
            return copyBoard(board);
        }

        public char getCell(int row, int column) {
            if (row < 0 || row >= size || column < 0 || column >= size) {
                throw new IllegalArgumentException("Coordinates are out of bounds");
            }
            return board[row][column];
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

        public String getVersionString() {
            return versionString;
        }
    }

    private final int size;
    private final char[][] board;
    private char currentPlayer;
    private char winner;
    private boolean draw;
    private long moveCount;
    private long generation;
    private long revision;
    private String versionString;

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
        draw = false;
        moveCount = 0;
        generation = 0;
        revision = 0;
        versionString = makeVersionString();
    }

    public MoveResult makeMove(char player, int row, int column,
                               long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates are out of bounds");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Revision cannot be negative");
        }

        synchronized (this) {
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
                throw new ArithmeticException("Revision overflow");
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
            versionString = makeVersionString();
            return result(Status.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (this) {
            if (revision == Long.MAX_VALUE || generation == Long.MAX_VALUE) {
                throw new ArithmeticException("Version counter overflow");
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
            versionString = makeVersionString();
            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private MoveResult result(Status status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(size, board, currentPlayer, winner, draw,
                moveCount, generation, revision, versionString);
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

    private String makeVersionString() {
        String version = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            return version + ".WIN_X";
        }
        if (winner == 'O') {
            return version + ".WIN_O";
        }
        if (draw) {
            return version + ".DRAW";
        }
        return version;
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
