@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}

    private static final int INITIAL_SIZE = 5;
    private static final char EMPTY = '.';
    private static final String WIN_X = ".WIN_X";
    private static final String WIN_O = ".WIN_O";
    private static final String DRAW = ".DRAW";

    private char[][] board;
    private int size;
    private char currentPlayer;
    private int moveCount;
    private char winner;
    private int generation;
    private long revision;

    public GomokuVersionState(int size) {
        if (size < INITIAL_SIZE) {
            throw new IllegalArgumentException("Board size must be >= 5");
        }
        this.size = size;
        board = new char[size][size];
        reset();
    }

    public Result makeMove(char player, int row, int column, long expectedRevision) {
        synchronized (this) {
            if (!isValidPlayer(player)) {
                return new Result(StatusCode.WRONG_PLAYER, revision, generation);
            }
            if (row < 0 || row >= size || column < 0 || column >= size) {
                return new Result(StatusCode.OUT_OF_BOUNDS, revision, generation);
            }
            if (expectedRevision != revision) {
                return new Result(StatusCode.STALE_REVISION, revision, generation);
            }
            if (board[row][column] != EMPTY) {
                return new Result(StatusCode.OCCUPIED, revision, generation);
            }
            if (winner != EMPTY) {
                return new Result(StatusCode.FINISHED, revision, generation);
            }

            board[row][column] = player;
            moveCount++;
            if (checkWinner(player, row, column)) {
                winner = player;
            } else if (moveCount == size * size) {
                winner = EMPTY; // Draw
            }

            currentPlayer = opponent(player);
            revision++;
            generation++;

            return new Result(StatusCode.SUCCESS, revision, generation, winner);
        }
    }

    private boolean isValidPlayer(char player) {
        return player == 'X' || player == 'O';
    }

    private char opponent(char player) {
        return player == 'X' ? 'O' : 'X';
    }

    private boolean checkWinner(char player, int row, int column) {
        return checkDirection(player, row, column, 0, 1) || // Horizontal
               checkDirection(player, row, column, 1, 0) || // Vertical
               checkDirection(player, row, column, 1, 1) || // Diagonal /
               checkDirection(player, row, column, -1, 1);   // Diagonal \
    }

    private boolean checkDirection(char player, int row, int column, int dr, int dc) {
        int count = 1;
        for (int i = 1; i < 5; i++) {
            if (row + dr * i >= 0 && row + dr * i < size &&
                column + dc * i >= 0 && column + dc * i < size &&
                board[row + dr * i][column + dc * i] == player) {
                count++;
            } else {
                break;
            }
        }
        for (int i = 1; i < 5; i++) {
            if (row - dr * i >= 0 && row - dr * i < size &&
                column - dc * i >= 0 && column - dc * i < size &&
                board[row - dr * i][column - dc * i] == player) {
                count++;
            } else {
                break;
            }
        }
        return count >= 5;
    }

    public void reset() {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    board[i][j] = EMPTY;
                }
            }
            currentPlayer = 'X';
            moveCount = 0;
            winner = EMPTY;
            generation++;
            revision++;
        }
    }

    public Result snapshot() {
        synchronized (this) {
            char[][] boardCopy = new char[size][size];
            for (int i = 0; i < size; i++) {
                System.arraycopy(board[i], 0, boardCopy[i], 0, size);
            }
            return new Result(StatusCode.SUCCESS, revision, generation, winner == EMPTY ? boardCopy : null);
        }
    }

    public static class Result {
        public enum StatusCode { SUCCESS, STALE_REVISION, OUT_OF_BOUNDS, WRONG_PLAYER, OCCUPIED, FINISHED }
        public final StatusCode status;
        public final long revision;
        public final int generation;
        public final char[][] board;

        public Result(StatusCode status, long revision, int generation) {
            this(status, revision, generation, null);
        }

        public Result(StatusCode status, long revision, int generation, char[][] board) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.board = board;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (board != null) {
                for (char[] row : board) {
                    sb.append(new String(row)).append("\n");
                }
            }
            return "Result{" +
                    "status=" + status +
                    ", revision=" + revision +
                    ", generation=" + generation +
                    ", board=\n" + sb +
                    '}';
        }
    }
}
