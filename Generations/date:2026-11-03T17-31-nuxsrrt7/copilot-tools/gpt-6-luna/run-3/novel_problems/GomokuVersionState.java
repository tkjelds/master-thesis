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
        public final long moveCount;
        public final char winner;
        public final boolean draw;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(int size, char[][] board, char currentPlayer,
                         long moveCount, char winner, boolean draw,
                         long generation, long revision, String version) {
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

        public char[][] getBoard() {
            return copyBoard(board);
        }

        public int getSize() {
            return size;
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }

        public long getMoveCount() {
            return moveCount;
        }

        public char getWinner() {
            return winner;
        }

        public boolean isDraw() {
            return draw;
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
        this.board = newEmptyBoard(size);
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
        synchronized (this) {
            if (player != 'X' && player != 'O') {
                throw new IllegalArgumentException("Invalid player");
            }
            if (row < 0 || row >= size || column < 0 || column >= size) {
                throw new IllegalArgumentException("Coordinates out of bounds");
            }
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("Revision cannot be negative");
            }

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
            boolean won = hasFive(row, column, player);
            boolean nextDraw = !won && nextMoveCount == (long) size * size;
            char nextWinner = won ? player : '.';
            char nextPlayer = (won || nextDraw)
                    ? currentPlayer
                    : (currentPlayer == 'X' ? 'O' : 'X');
            long nextRevision = revision + 1L;
            String nextVersion = makeVersion(
                    size, nextMoveCount, nextPlayer, nextWinner, nextDraw);

            board[row][column] = player;
            moveCount = nextMoveCount;
            winner = nextWinner;
            draw = nextDraw;
            currentPlayer = nextPlayer;
            revision = nextRevision;
            version = nextVersion;

            return result(MoveStatus.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (this) {
            if (generation == Long.MAX_VALUE) {
                throw new ArithmeticException("Generation overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision overflow");
            }

            char[][] emptyBoard = newEmptyBoard(size);
            long nextGeneration = generation + 1L;
            long nextRevision = revision + 1L;
            String nextVersion = makeVersion(size, 0L, 'X', '.', false);
            Snapshot snapshot = new Snapshot(
                    size, emptyBoard, 'X', 0L, '.', false,
                    nextGeneration, nextRevision, nextVersion);

            board = emptyBoard;
            currentPlayer = 'X';
            moveCount = 0L;
            winner = '.';
            draw = false;
            generation = nextGeneration;
            revision = nextRevision;
            version = nextVersion;

            return snapshot;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(size, board, currentPlayer, moveCount, winner,
                    draw, generation, revision, version);
        }
    }

    private MoveResult result(MoveStatus status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private boolean hasFive(int row, int column, char player) {
        int[][] directions = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int i = 0; i < directions.length; i++) {
            int dr = directions[i][0];
            int dc = directions[i][1];
            long count = 1L + countDirection(row, column, dr, dc, player)
                    + countDirection(row, column, -dr, -dc, player);
            if (count >= 5L) {
                return true;
            }
        }
        return false;
    }

    private long countDirection(int row, int column, int dr, int dc,
                                char player) {
        long count = 0L;
        int r = row + dr;
        int c = column + dc;
        while (r >= 0 && r < size && c >= 0 && c < size
                && board[r][c] == player) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
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
        char[][] result = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = new char[source[row].length];
            for (int column = 0; column < source[row].length; column++) {
                result[row][column] = source[row][column];
            }
        }
        return result;
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
