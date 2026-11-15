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
        private final Status status;
        private final long revision;
        private final long generation;
        private final char winner;

        private MoveResult(Status status, long revision, long generation, char winner) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
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

        /** Returns 'X', 'O', or '.' when there is no winner. */
        public char getWinner() {
            return winner;
        }
    }

    public static final class Snapshot {
        private final char[][] board;
        private final char currentPlayer;
        private final char winner;
        private final boolean draw;
        private final int moveCount;
        private final long generation;
        private final long revision;
        private final String version;

        private Snapshot(char[][] board, char currentPlayer, char winner, boolean draw,
                         int moveCount, long generation, long revision, String version) {
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
            char[][] copy = new char[board.length][];
            for (int i = 0; i < board.length; i++) {
                copy[i] = board[i].clone();
            }
            return copy;
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

        public boolean isFinished() {
            return draw || winner != '.';
        }

        public int getMoveCount() {
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

    private final Object lock = new Object();
    private final int size;
    private final char[][] board;
    private char currentPlayer;
    private char winner;
    private boolean draw;
    private int moveCount;
    private long generation;
    private long revision;

    public GomokuVersionState(int boardSize) {
        if (boardSize < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }
        this.size = boardSize;
        this.board = new char[boardSize][boardSize];
        for (int i = 0; i < boardSize; i++) {
            for (int j = 0; j < boardSize; j++) {
                board[i][j] = EMPTY;
            }
        }
        this.currentPlayer = 'X';
        this.winner = EMPTY;
        this.draw = false;
        this.moveCount = 0;
        this.generation = 0;
        this.revision = 0;
    }

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Invalid player");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates out of bounds");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative revision");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return fail(Status.STALE_REVISION);
            }
            if (winner != EMPTY || draw) {
                return fail(Status.FINISHED);
            }
            if (player != currentPlayer) {
                return fail(Status.WRONG_TURN);
            }
            if (board[row][column] != EMPTY) {
                return fail(Status.OCCUPIED);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }
            board[row][column] = player;
            moveCount++;
            revision++;
            if (hasFive(player, row, column)) {
                winner = player;
            } else if (moveCount == size * size) {
                draw = true;
            } else {
                currentPlayer = (player == 'X') ? 'O' : 'X';
            }
            return new MoveResult(Status.SUCCESS, revision, generation, winner);
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE || generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision or generation overflow");
            }
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    board[i][j] = EMPTY;
                }
            }
            currentPlayer = 'X';
            winner = EMPTY;
            draw = false;
            moveCount = 0;
            generation++;
            revision++;
            return buildSnapshot();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return buildSnapshot();
        }
    }

    // Must be called while holding lock.
    private MoveResult fail(Status status) {
        return new MoveResult(status, revision, generation, winner);
    }

    // Must be called while holding lock.
    private Snapshot buildSnapshot() {
        char[][] copy = new char[size][];
        for (int i = 0; i < size; i++) {
            copy[i] = board[i].clone();
        }
        String v = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            v += ".WIN_X";
        } else if (winner == 'O') {
            v += ".WIN_O";
        } else if (draw) {
            v += ".DRAW";
        }
        return new Snapshot(copy, currentPlayer, winner, draw, moveCount, generation, revision, v);
    }

    // Must be called while holding lock.
    private boolean hasFive(char player, int row, int col) {
        int[][] dirs = { {0, 1}, {1, 0}, {1, 1}, {1, -1} };
        for (int[] d : dirs) {
            int count = 1;
            count += countDir(player, row, col, d[0], d[1]);
            count += countDir(player, row, col, -d[0], -d[1]);
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    private int countDir(char player, int row, int col, int dr, int dc) {
        int n = 0;
        int r = row + dr;
        int c = col + dc;
        while (r >= 0 && r < size && c >= 0 && c < size && board[r][c] == player) {
            n++;
            r += dr;
            c += dc;
        }
        return n;
    }
}
