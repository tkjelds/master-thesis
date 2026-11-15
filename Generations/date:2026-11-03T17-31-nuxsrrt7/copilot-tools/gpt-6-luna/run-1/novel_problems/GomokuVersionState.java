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

        private Snapshot(
                char[][] board,
                char currentPlayer,
                char winner,
                boolean draw,
                long moveCount,
                long generation,
                long revision,
                String version) {
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

    private static final char EMPTY = '.';

    private final Object mutex = new Object();
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
        this.board = createEmptyBoard(size);
        this.currentPlayer = 'X';
        this.winner = EMPTY;
        this.draw = false;
        this.moveCount = 0L;
        this.generation = 0L;
        this.revision = 0L;
        this.version = buildVersion(size, moveCount, currentPlayer, winner, draw);
    }

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Move coordinates are out of bounds");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }

        synchronized (mutex) {
            if (expectedRevision != revision) {
                return result(MoveStatus.STALE_REVISION);
            }
            if (winner != EMPTY || draw) {
                return result(MoveStatus.FINISHED);
            }
            if (player != currentPlayer) {
                return result(MoveStatus.WRONG_TURN);
            }
            if (board[row][column] != EMPTY) {
                return result(MoveStatus.OCCUPIED);
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
            version = buildVersion(size, moveCount, currentPlayer, winner, draw);
            return result(MoveStatus.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (mutex) {
            if (revision == Long.MAX_VALUE || generation == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision or generation overflow");
            }

            char[][] clearedBoard = createEmptyBoard(size);
            board = clearedBoard;
            currentPlayer = 'X';
            winner = EMPTY;
            draw = false;
            moveCount = 0L;
            generation++;
            revision++;
            version = buildVersion(size, moveCount, currentPlayer, winner, draw);
            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (mutex) {
            return snapshotLocked();
        }
    }

    private MoveResult result(MoveStatus status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                board, currentPlayer, winner, draw, moveCount, generation, revision, version);
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

    private int countInDirection(int row, int column, char player, int rowStep, int columnStep) {
        int count = 0;
        int currentRow = row;
        int currentColumn = column;

        while (currentRow >= 0 && currentRow < size
                && currentColumn >= 0 && currentColumn < size
                && board[currentRow][currentColumn] == player) {
            count++;
            currentRow += rowStep;
            currentColumn += columnStep;
        }

        return count;
    }

    private static char[][] createEmptyBoard(int size) {
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

    private static String buildVersion(
            int size, long moveCount, char currentPlayer, char winner, boolean draw) {
        String result = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner != EMPTY) {
            result += winner == 'X' ? ".WIN_X" : ".WIN_O";
        } else if (draw) {
            result += ".DRAW";
        }
        return result;
    }
}
