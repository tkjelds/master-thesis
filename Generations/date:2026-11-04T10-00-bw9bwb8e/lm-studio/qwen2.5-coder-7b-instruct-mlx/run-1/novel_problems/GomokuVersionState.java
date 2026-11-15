@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}

    private static final char EMPTY = '.';
    private static final char X = 'X';
    private static final char O = 'O';

    private final int size;
    private char[][] board;
    private char currentPlayer;
    private int moveCount;
    private char winner;
    private long revision;
    private int generation;

    public GomokuVersionState(int size) {
        if (size < 5) throw new IllegalArgumentException("Board size must be >= 5");
        this.size = size;
        board = new char[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                board[i][j] = EMPTY;
            }
        }
        currentPlayer = X;
        moveCount = 0;
        winner = EMPTY;
        revision = 0;
        generation = 0;
    }

    public Result makeMove(char player, int row, int column, long expectedRevision) {
        synchronized (this) {
            if (player != currentPlayer || revision != expectedRevision) return getFailedResult();
            if (row < 0 || row >= size || column < 0 || column >= size) return getFailedResult();
            if (board[row][column] != EMPTY) return Result.OCCUPIED;

            board[row][column] = player;
            revision++;
            moveCount++;

            if (checkWin(player, row, column)) {
                winner = player;
            } else if (moveCount == size * size) {
                winner = EMPTY; // Draw
            }

            currentPlayer = player == X ? O : X;

            return new Result(currentPlayer, revision, generation, winner);
        }
    }

    private boolean checkWin(char player, int row, int column) {
        return checkDirection(player, row, column, 0, 1) // Horizontal
                || checkDirection(player, row, column, 1, 0) // Vertical
                || checkDirection(player, row, column, 1, 1) // Downward diagonal
                || checkDirection(player, row, column, -1, 1); // Upward diagonal
    }

    private boolean checkDirection(char player, int row, int column, int dr, int dc) {
        int count = 1;
        for (int i = 1; i < 5 && row + dr * i >= 0 && row + dr * i < size && column + dc * i >= 0 && column + dc * i < size; i++) {
            if (board[row + dr * i][column + dc * i] == player) count++;
            else break;
        }
        for (int i = 1; i < 5 && row - dr * i >= 0 && row - dr * i < size && column - dc * i >= 0 && column - dc * i < size; i++) {
            if (board[row - dr * i][column - dc * i] == player) count++;
            else break;
        }
        return count >= 5;
    }

    public void reset() {
        synchronized (this) {
            board = new char[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    board[i][j] = EMPTY;
                }
            }
            currentPlayer = X;
            moveCount = 0;
            winner = EMPTY;
            revision++;
            generation++;

            Result snapshot = this.snapshot();
            System.out.println(new String(snapshot.board));
            System.out.println("Turn: " + snapshot.currentPlayer);
            if (snapshot.winner == X || snapshot.winner == O) {
                System.out.println("Winner: Player " + snapshot.winner);
            } else if (snapshot.winner == EMPTY) {
                System.out.println("Draw");
            }
        }
    }

    public Result snapshot() {
        synchronized (this) {
            char[][] copiedBoard = new char[size][size];
            for (int i = 0; i < size; i++) {
                System.arraycopy(board, i, copiedBoard, i, size);
            }
            return new Result(currentPlayer, revision, generation, winner, copiedBoard);
        }
    }

    private Result getFailedResult() {
        return new Result(currentPlayer, revision, generation, EMPTY);
    }

    public static class Result {
        private final char currentPlayer;
        private final long revision;
        private final int generation;
        private final char winner;
        private final char[][] board;

        public Result(char currentPlayer, long revision, int generation) {
            this(currentPlayer, revision, generation, EMPTY);
        }

        public Result(char currentPlayer, long revision, int generation, char winner) {
            this(currentPlayer, revision, generation, winner, null);
        }

        public Result(char currentPlayer, long revision, int generation, char winner, char[][] board) {
            this.currentPlayer = currentPlayer;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.board = board;
        }

        public char[][] getBoard() {
            return board;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (board != null) {
                for (char[] row : board) {
                    for (char cell : row) {
                        sb.append(cell);
                    }
                    sb.append("\n");
                }
            }
            sb.append("Turn: ").append(currentPlayer);
            if (winner == X || winner == O) {
                sb.append("\nWinner: Player ").append(winner);
            } else if (winner == EMPTY) {
                sb.append("\nDraw");
            }
            return sb.toString();
        }
    }
}
