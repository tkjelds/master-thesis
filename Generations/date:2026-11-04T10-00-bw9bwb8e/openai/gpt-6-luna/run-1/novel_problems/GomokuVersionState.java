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
        public final char currentPlayer;
        public final long moveCount;
        public final String version;

        private MoveResult(
                MoveStatus status,
                long revision,
                long generation,
                char winner,
                boolean draw,
                char currentPlayer,
                long moveCount,
                String version) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.draw = draw;
            this.currentPlayer = currentPlayer;
            this.moveCount = moveCount;
            this.version = version;
        }
    }

    public static final class Snapshot {
        public final int size;
        public final char[][] board;
        public final char currentPlayer;
        public final long moveCount;
        public final char winner;
        public final boolean draw;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(
                int size,
                char[][] board,
                char currentPlayer,
                long moveCount,
                char winner,
                boolean draw,
                long generation,
                long revision,
                String version) {
            this.size = size;
            this.board = copyBoard(board);
            this.currentPlayer = currentPlayer;
            this.moveCount = moveCount;
            this.winner = winner;
            this.draw = draw;
            this.generation = generation;
            this.revision = revision;
            this.version = version;
        }
    }

    private final Object lock = new Object();
    private final int size;

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

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates are outside the board");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return currentResult(MoveStatus.STALE_REVISION);
            }
            if (winner != '.' || draw) {
                return currentResult(MoveStatus.FINISHED);
            }
            if (player != currentPlayer) {
                return currentResult(MoveStatus.WRONG_TURN);
            }
            if (board[row][column] != '.') {
                return currentResult(MoveStatus.OCCUPIED);
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision overflow");
            }

            long newMoveCount = moveCount + 1L;
            boolean won = makesFive(row, column, player);
            boolean newDraw = !won && newMoveCount == (long) size * (long) size;
            char newWinner = won ? player : '.';
            char newCurrentPlayer = (won || newDraw)
                    ? currentPlayer
                    : (currentPlayer == 'X' ? 'O' : 'X');
            long newRevision = revision + 1L;
            String newVersion = makeVersion(
                    size, newMoveCount, newCurrentPlayer, newWinner, newDraw);

            MoveResult result = new MoveResult(
                    MoveStatus.SUCCESS,
                    newRevision,
                    generation,
                    newWinner,
                    newDraw,
                    newCurrentPlayer,
                    newMoveCount,
                    newVersion);

            board[row][column] = player;
            moveCount = newMoveCount;
            winner = newWinner;
            draw = newDraw;
            currentPlayer = newCurrentPlayer;
            revision = newRevision;
            version = newVersion;

            return result;
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE || generation == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision or generation overflow");
            }

            char[][] newBoard = emptyBoard(size);
            long newGeneration = generation + 1L;
            long newRevision = revision + 1L;
            char newCurrentPlayer = 'X';
            long newMoveCount = 0L;
            char newWinner = '.';
            boolean newDraw = false;
            String newVersion = makeVersion(
                    size, newMoveCount, newCurrentPlayer, newWinner, newDraw);

            board = newBoard;
            currentPlayer = newCurrentPlayer;
            moveCount = newMoveCount;
            winner = newWinner;
            draw = newDraw;
            generation = newGeneration;
            revision = newRevision;
            version = newVersion;

            return currentSnapshot();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return currentSnapshot();
        }
    }

    private MoveResult currentResult(MoveStatus status) {
        return new MoveResult(
                status,
                revision,
                generation,
                winner,
                draw,
                currentPlayer,
                moveCount,
                version);
    }

    private Snapshot currentSnapshot() {
        return new Snapshot(
                size,
                board,
                currentPlayer,
                moveCount,
                winner,
                draw,
                generation,
                revision,
                version);
    }

    private boolean makesFive(int row, int column, char player) {
        return hasFiveInDirection(row, column, player, 0, 1)
                || hasFiveInDirection(row, column, player, 1, 0)
                || hasFiveInDirection(row, column, player, 1, 1)
                || hasFiveInDirection(row, column, player, 1, -1);
    }

    private boolean hasFiveInDirection(
            int row, int column, char player, int rowStep, int columnStep) {
        int count = 1;
        count += countInDirection(row, column, player, rowStep, columnStep);
        count += countInDirection(row, column, player, -rowStep, -columnStep);
        return count >= 5;
    }

    private int countInDirection(
            int row, int column, char player, int rowStep, int columnStep) {
        int count = 0;
        int r = row + rowStep;
        int c = column + columnStep;

        while (r >= 0 && r < size && c >= 0 && c < size && board[r][c] == player) {
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
        char[][] result = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = new char[source[row].length];
            for (int column = 0; column < source[row].length; column++) {
                result[row][column] = source[row][column];
            }
        }
        return result;
    }

    private static String makeVersion(
            int size, long moveCount, char currentPlayer, char winner, boolean draw) {
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
