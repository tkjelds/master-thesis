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

        public char getWinner() {
            return winner;
        }
    }

    public static final class Snapshot {
        private final int size;
        private final char[][] board;
        private final char currentPlayer;
        private final long moveCount;
        private final char winner;
        private final boolean draw;
        private final long generation;
        private final long revision;
        private final String version;

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

        public int getSize() {
            return size;
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

        public boolean isFinished() {
            return winner != '.' || draw;
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

    private final Object monitor = new Object();
    private final int size;
    private final long capacity;

    private char[][] board;
    private char currentPlayer;
    private long moveCount;
    private char winner;
    private boolean draw;
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
    }

    // Operations wait only to acquire the monitor, not for game conditions.
    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates are outside the board");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Revision must be nonnegative");
        }

        synchronized (monitor) {
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
                throw new IllegalStateException("Revision overflow");
            }

            board[row][column] = player;
            moveCount++;

            if (hasFive(row, column, player)) {
                winner = player;
            } else if (moveCount == capacity) {
                draw = true;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
            }

            revision++;
            return result(Status.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (monitor) {
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }
            if (generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation overflow");
            }

            char[][] newBoard = emptyBoard(size);
            board = newBoard;
            currentPlayer = 'X';
            moveCount = 0;
            winner = '.';
            draw = false;
            generation++;
            revision++;
            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return snapshotLocked();
        }
    }

    private MoveResult result(Status status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private Snapshot snapshotLocked() {
        String version = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner != '.') {
            version += ".WIN_" + winner;
        } else if (draw) {
            version += ".DRAW";
        }
        return new Snapshot(
                size, board, currentPlayer, moveCount, winner, draw,
                generation, revision, version);
    }

    private boolean hasFive(int row, int column, char player) {
        return hasFiveInDirection(row, column, player, 0, 1)
                || hasFiveInDirection(row, column, player, 1, 0)
                || hasFiveInDirection(row, column, player, 1, 1)
                || hasFiveInDirection(row, column, player, 1, -1);
    }

    private boolean hasFiveInDirection(
            int row, int column, char player, int rowStep, int columnStep) {
        int count = 1;
        for (int direction = -1; direction <= 1; direction += 2) {
            int r = row + direction * rowStep;
            int c = column + direction * columnStep;
            while (r >= 0 && r < size && c >= 0 && c < size
                    && board[r][c] == player) {
                count++;
                if (count >= 5) {
                    return true;
                }
                r += direction * rowStep;
                c += direction * columnStep;
            }
        }
        return false;
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
