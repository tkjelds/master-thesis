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

        public Status getStatus() { return status; }
        public long getRevision() { return revision; }
        public long getGeneration() { return generation; }
        /** 'X' or 'O' if there is a winner, otherwise '.'. */
        public char getWinner() { return winner; }

        @Override
        public String toString() {
            return "MoveResult{" + status + ", rev=" + revision + ", gen=" + generation
                    + ", winner=" + winner + "}";
        }
    }

    public static final class Snapshot {
        private final char[][] board;
        private final char currentPlayer;
        private final char winner;
        private final boolean draw;
        private final int moveCount;
        private final String version;
        private final long generation;
        private final long revision;

        private Snapshot(char[][] board, char currentPlayer, char winner, boolean draw,
                         int moveCount, String version, long generation, long revision) {
            this.board = board;
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.moveCount = moveCount;
            this.version = version;
            this.generation = generation;
            this.revision = revision;
        }

        public char[][] getBoard() {
            char[][] copy = new char[board.length][];
            for (int i = 0; i < board.length; i++) {
                copy[i] = board[i].clone();
            }
            return copy;
        }

        public int getSize() { return board.length; }
        public char getCurrentPlayer() { return currentPlayer; }
        public char getWinner() { return winner; }
        public boolean isDraw() { return draw; }
        public boolean isFinished() { return draw || winner != '.'; }
        public int getMoveCount() { return moveCount; }
        public String getVersion() { return version; }
        public long getGeneration() { return generation; }
        public long getRevision() { return revision; }
    }

    private static final char EMPTY = '.';

    private final Object lock = new Object();
    private final int size;

    // All below guarded by lock
    private final char[][] board;
    private char currentPlayer;
    private char winner;
    private boolean draw;
    private int moveCount;
    private long generation;
    private long revision;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("size must be >= 5");
        }
        this.size = size;
        this.board = new char[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                board[i][j] = EMPTY;
            }
        }
        this.currentPlayer = 'X';
        this.winner = EMPTY;
        this.draw = false;
        this.moveCount = 0;
        this.generation = 0L;
        this.revision = 0L;
    }

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("invalid player");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("coordinates out of bounds");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("negative revision");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MoveResult(Status.STALE_REVISION, revision, generation, winner);
            }
            if (winner != EMPTY || draw) {
                return new MoveResult(Status.FINISHED, revision, generation, winner);
            }
            if (player != currentPlayer) {
                return new MoveResult(Status.WRONG_TURN, revision, generation, winner);
            }
            if (board[row][column] != EMPTY) {
                return new MoveResult(Status.OCCUPIED, revision, generation, winner);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            board[row][column] = player;
            moveCount++;
            revision++;
            if (isWinningMove(player, row, column)) {
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
                throw new IllegalStateException("revision or generation overflow");
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

    // Must be called holding lock.
    private Snapshot buildSnapshot() {
        char[][] copy = new char[size][];
        for (int i = 0; i < size; i++) {
            copy[i] = board[i].clone();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(size).append('.').append(moveCount).append('.').append((int) currentPlayer);
        if (winner == 'X') {
            sb.append(".WIN_X");
        } else if (winner == 'O') {
            sb.append(".WIN_O");
        } else if (draw) {
            sb.append(".DRAW");
        }
        return new Snapshot(copy, currentPlayer, winner, draw, moveCount,
                sb.toString(), generation, revision);
    }

    // Must be called holding lock.
    private boolean isWinningMove(char player, int row, int col) {
        int[][] dirs = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            int count = 1;
            int r = row + d[0];
            int c = col + d[1];
            while (r >= 0 && r < size && c >= 0 && c < size && board[r][c] == player) {
                count++;
                r += d[0];
                c += d[1];
            }
            r = row - d[0];
            c = col - d[1];
            while (r >= 0 && r < size && c >= 0 && c < size && board[r][c] == player) {
                count++;
                r -= d[0];
                c -= d[1];
            }
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }
}
