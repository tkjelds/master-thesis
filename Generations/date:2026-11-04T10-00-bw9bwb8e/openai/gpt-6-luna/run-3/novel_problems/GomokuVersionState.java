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

        public boolean isSuccessful() {
            return status == Status.SUCCESS;
        }
    }

    public static final class Snapshot {
        public final char[][] board;
        public final char currentPlayer;
        public final long moveCount;
        public final char winner;
        public final boolean draw;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(char[][] sourceBoard, char currentPlayer, long moveCount,
                         char winner, boolean draw, long generation,
                         long revision, String version) {
            this.board = copyBoard(sourceBoard);
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
        this.board = emptyBoard(size);
        this.currentPlayer = 'X';
        this.moveCount = 0;
        this.winner = '.';
        this.draw = false;
        this.generation = 0;
        this.revision = 0;
        this.version = makeVersion(size, moveCount, currentPlayer, winner, draw);
    }

    public MoveResult makeMove(char player, int row, int column,
                               long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Move is outside the board");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
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

            long nextMoveCount = moveCount + 1;
            char nextWinner = hasFive(row, column, player) ? player : '.';
            boolean nextDraw = nextWinner == '.' &&
                    nextMoveCount == (long) size * (long) size;
            char nextPlayer = (nextWinner != '.' || nextDraw)
                    ? currentPlayer
                    : (currentPlayer == 'X' ? 'O' : 'X');
            long nextRevision = revision + 1;
            String nextVersion = makeVersion(
                    size, nextMoveCount, nextPlayer, nextWinner, nextDraw);

            board[row][column] = player;
            moveCount = nextMoveCount;
            winner = nextWinner;
            draw = nextDraw;
            currentPlayer = nextPlayer;
            revision = nextRevision;
            version = nextVersion;

            return result(Status.SUCCESS);
        }
    }

    public Snapshot reset() {
        char[][] clearedBoard = emptyBoard(size);

        synchronized (this) {
            if (generation == Long.MAX_VALUE) {
                throw new ArithmeticException("Generation overflow");
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision overflow");
            }

            long nextGeneration = generation + 1;
            long nextRevision = revision + 1;
            String nextVersion = makeVersion(size, 0, 'X', '.', false);

            board = clearedBoard;
            currentPlayer = 'X';
            moveCount = 0;
            winner = '.';
            draw = false;
            generation = nextGeneration;
            revision = nextRevision;
            version = nextVersion;

            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    public int getSize() {
        return size;
    }

    private MoveResult result(Status status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(board, currentPlayer, moveCount, winner, draw,
                generation, revision, version);
    }

    private boolean hasFive(int row, int column, char player) {
        int[][] directions = {
                {0, 1},
                {1, 0},
                {1, 1},
                {1, -1}
        };

        for (int i = 0; i < directions.length; i++) {
            int dr = directions[i][0];
            int dc = directions[i][1];
            int count = 1;

            count += countDirection(row, column, dr, dc, player);
            count += countDirection(row, column, -dr, -dc, player);

            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    private int countDirection(int row, int column, int dr, int dc,
                               char player) {
        int count = 0;
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

    private static String makeVersion(int size, long moveCount,
                                     char currentPlayer, char winner,
                                     boolean draw) {
        String result = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            result += ".WIN_X";
        } else if (winner == 'O') {
            result += ".WIN_O";
        } else if (draw) {
            result += ".DRAW";
        }
        return result;
    }
}
