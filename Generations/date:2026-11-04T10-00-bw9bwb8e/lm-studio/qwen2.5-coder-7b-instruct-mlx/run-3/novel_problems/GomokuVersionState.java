@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}

    private final int size;
    private char[][] board;
    private char currentPlayer = 'X';
    private int moveCount = 0;
    private Character winner;
    private long revision = 0;
    private int generation = 0;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be >= 5");
        }
        this.size = size;
        this.board = new char[size][size];
    }

    public synchronized Result makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Invalid player");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        if (revision != expectedRevision) {
            return new Result(Status.STALE_REVISION, revision, generation, winner);
        }
        if (winner != null || moveCount >= size * size) {
            return new Result(Status.FINISHED, revision, generation, winner);
        }
        if (board[row][column] != '\0') {
            return new Result(Status.OCCUPIED, revision, generation, winner);
        }

        board[row][column] = player;
        moveCount++;
        if (checkWin(player, row, column)) {
            winner = player;
        }

        revision++;
        currentPlayer = (currentPlayer == 'X') ? 'O' : 'X';

        return new Result(Status.SUCCESS, revision, generation, winner);
    }

    private boolean checkWin(char player, int row, int column) {
        return checkDirection(player, row, column, 0, 1) || // Horizontal
               checkDirection(player, row, column, 1, 0) || // Vertical
               checkDirection(player, row, column, 1, 1) || // Diagonal /
               checkDirection(player, row, column, 1, -1);   // Diagonal \
    }

    private boolean checkDirection(char player, int row, int column, int dRow, int dCol) {
        for (int i = 1; i < 5; i++) {
            int r = row + dRow * i;
            int c = column + dCol * i;
            if (r < 0 || r >= size || c < 0 || c >= size || board[r][c] != player) {
                return false;
            }
        }
        return true;
    }

    public synchronized void reset() {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                board[i][j] = '\0';
            }
        }
        currentPlayer = 'X';
        moveCount = 0;
        winner = null;

        generation++;
        revision++;

        notifyAll();
    }

    public synchronized Snapshot snapshot() {
        char[][] boardSnapshot = new char[size][size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(board[i], 0, boardSnapshot[i], 0, size);
        }
        return new Snapshot(boardSnapshot, currentPlayer, moveCount, winner, generation, revision);
    }

    public static class Result {
        public final Status status;
        public final long revision;
        public final int generation;
        public final Character winner;

        private Result(Status status, long revision, int generation, Character winner) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
        }

        @Override
        public String toString() {
            return "Result{" +
                   "status=" + status +
                   ", revision=" + revision +
                   ", generation=" + generation +
                   ", winner=" + winner +
                   '}';
        }

        public enum Status {
            SUCCESS,
            STALE_REVISION,
            FINISHED,
            WRONG_TURN,
            OCCUPIED
        }
    }

    public static class Snapshot {
        public final char[][] board;
        public final char currentPlayer;
        public final int moveCount;
        public final Character winner;
        public final int generation;
        public final long revision;

        private Snapshot(char[][] board, char currentPlayer, int moveCount, Character winner, int generation, long revision) {
            this.board = board;
            this.currentPlayer = currentPlayer;
            this.moveCount = moveCount;
            this.winner = winner;
            this.generation = generation;
            this.revision = revision;
        }

        @Override
        public String toString() {
            return "Snapshot{" +
                   "board=" + Arrays.deepToString(board) +
                   ", currentPlayer=" + currentPlayer +
                   ", moveCount=" + moveCount +
                   ", winner=" + winner +
                   ", generation=" + generation +
                   ", revision=" + revision +
                   '}';
        }
    }
}
