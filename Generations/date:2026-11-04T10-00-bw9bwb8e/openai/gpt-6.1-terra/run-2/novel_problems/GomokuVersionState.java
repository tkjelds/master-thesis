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

        private MoveResult(
                MoveStatus status, long revision, long generation, char winner) {
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
                long revision) {
            this.size = size;
            this.board = copyBoard(board);
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.moveCount = moveCount;
            this.generation = generation;
            this.revision = revision;

            String display = size + "." + moveCount + "." + (int) currentPlayer;
            if (winner == 'X') {
                display += ".WIN_X";
            } else if (winner == 'O') {
                display += ".WIN_O";
            } else if (draw) {
                display += ".DRAW";
            }
            this.version = display;
        }

        public char[][] getBoard() {
            return copyBoard(board);
        }
    }

    private final Object lock = new Object();
    private final int size;
    private final long capacity;

    // All mutable state is guarded by lock.
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
        this.capacity = (long) size * size;
        this.board = emptyBoard(size);
        this.currentPlayer = 'X';
        this.winner = '.';
        this.draw = false;
        this.moveCount = 0;
        this.generation = 0;
        this.revision = 0;
    }

    // Operations wait only for the intrinsic lock; they do not wait for a
    // game condition to change.
    public MoveResult makeMove(
            char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates out of bounds");
        }
        if (expectedRevision < 0) {
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
                throw new ArithmeticException("Revision overflow");
            }

            boolean wins = isWinningMove(player, row, column);
            long nextCount = moveCount + 1;
            char nextWinner = wins ? player : '.';
            boolean nextDraw = !wins && nextCount == capacity;
            char nextPlayer = currentPlayer;
            if (!wins && !nextDraw) {
                nextPlayer = player == 'X' ? 'O' : 'X';
            }
            long nextRevision = revision + 1;

            MoveResult result = new MoveResult(
                    MoveStatus.SUCCESS, nextRevision, generation, nextWinner);

            board[row][column] = player;
            moveCount = nextCount;
            winner = nextWinner;
            draw = nextDraw;
            currentPlayer = nextPlayer;
            revision = nextRevision;
            return result;
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision overflow");
            }
            if (generation == Long.MAX_VALUE) {
                throw new ArithmeticException("Generation overflow");
            }

            char[][] nextBoard = emptyBoard(size);
            long nextRevision = revision + 1;
            long nextGeneration = generation + 1;
            Snapshot result = new Snapshot(
                    size, nextBoard, 'X', '.', false, 0,
                    nextGeneration, nextRevision);

            board = nextBoard;
            currentPlayer = 'X';
            winner = '.';
            draw = false;
            moveCount = 0;
            generation = nextGeneration;
            revision = nextRevision;
            return result;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    size, board, currentPlayer, winner, draw, moveCount,
                    generation, revision);
        }
    }

    private MoveResult result(MoveStatus status) {
        return new MoveResult(status, revision, generation, winner);
    }

    // The candidate cell is empty; count matching neighbors on both sides.
    private boolean isWinningMove(char player, int row, int column) {
        return lineLength(player, row, column, 0, 1) >= 5
                || lineLength(player, row, column, 1, 0) >= 5
                || lineLength(player, row, column, 1, 1) >= 5
                || lineLength(player, row, column, 1, -1) >= 5;
    }

    private int lineLength(
            char player, int row, int column, int rowStep, int columnStep) {
        return 1
                + countDirection(player, row, column, rowStep, columnStep)
                + countDirection(player, row, column, -rowStep, -columnStep);
    }

    private int countDirection(
            char player, int row, int column, int rowStep, int columnStep) {
        int count = 0;
        for (int distance = 1; distance <= 4; distance++) {
            long r = (long) row + (long) rowStep * distance;
            long c = (long) column + (long) columnStep * distance;
            if (r < 0 || r >= size || c < 0 || c >= size
                    || board[(int) r][(int) c] != player) {
                break;
            }
            count++;
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
}
