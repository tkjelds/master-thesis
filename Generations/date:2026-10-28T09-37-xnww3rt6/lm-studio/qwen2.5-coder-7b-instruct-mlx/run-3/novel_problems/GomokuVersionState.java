```java
public class GomokuVersionState {
    private static final char EMPTY = '.';
    private static final char X = 'X';
    private static final char O = 'O';

    private int size;
    private char[][] board;
    private char currentPlayer;
    private int moveCount;
    private String winner;
    private long revision;
    private long generation;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }
        this.size = size;
        board = new char[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                board[i][j] = EMPTY;
            }
        }
        currentPlayer = X;
        moveCount = 0;
        winner = null;
        revision = 0;
        generation = 0;
    }

    public Result makeMove(char player, int row, int column, long expectedRevision) {
        synchronized (this) {
            if (player != X && player != O) {
                throw new IllegalArgumentException("Invalid player");
            }
            if (row < 0 || row >= size || column < 0 || column >= size) {
                throw new IllegalArgumentException("Out-of-bounds coordinates");
            }
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("Negative revision");
            }
            if (expectedRevision != revision) {
                return Result.STALE_REVISION;
            }
            if (winner != null || moveCount >= size * size) {
                return Result.FINISHED;
            }
            if (board[row][column] != EMPTY) {
                return Result.OCCUPIED;
            }
            board[row][column] = player;
            moveCount++;
            if (checkWin(player, row, column)) {
                winner = String.valueOf(player);
            }
            revision++;
            return Result.success(revision, generation, moveCount, winner);
        }
    }

    public void reset() {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    board[i][j] = EMPTY;
                }
            }
            currentPlayer = X;
            moveCount = 0;
            winner = null;
            revision++;
            generation++;
        }
    }

    public Result snapshot() {
        synchronized (this) {
            char[][] copiedBoard = new char[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    copiedBoard[i][j] = board[i][j];
                }
            }
            return Result.success(revision, generation, moveCount, winner);
        }
    }

    private boolean checkWin(char player, int row, int column) {
        return checkDirection(player, row, column, 0, 1) || // Horizontal
               checkDirection(player, row, column, 1, 0) || // Vertical
               checkDirection(player, row, column, 1, 1) || // Diagonal /
               checkDirection(player, row, column, -1, 1);   // Diagonal \
    }

    private boolean checkDirection(char player, int row, int column, int dRow, int dColumn) {
        for (int i = 0; i < 4; i++) {
            int r = row + dRow * (i + 1);
            int c = column + dColumn * (i + 1);
            if (r < 0 || r >= size || c < 0 || c >= size) {
                return false;
            }
            if (board[r][c] != player) {
                return false;
            }
        }
        for (int i = 1; i < 4; i++) {
            int r = row - dRow * i;
            int c = column - dColumn * i;
            if (r < 0 || r >= size || c < 0 || c >= size) {
                return false;
            }
            if (board[r][c] != player) {
                return false;
            }
        }
        return true;
    }

    public static class Result {
        private final int status;
        private final long revision;
        private final long generation;
        private final String winner;

        private Result(int status, long revision, long generation, String winner) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
        }

        public static Result success(long revision, long generation, int moveCount, String winner) {
            return new Result(0, revision, generation, winner);
        }

        public static final Result STALE_REVISION = new Result(1, -1, -1, null);
        public static final Result FINISHED = new Result(2, -1, -1, null);
        public static final Result WRONG_TURN = new Result(3, -1, -1, null);
        public static final Result OCCUPIED = new Result(4, -1, -1, null);
    }
}
```
