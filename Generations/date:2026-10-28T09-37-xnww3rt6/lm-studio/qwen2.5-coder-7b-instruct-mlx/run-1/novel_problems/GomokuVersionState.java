@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}

    private static final int DEFAULT_SIZE = 15;
    private char[][] board;
    private char currentPlayer;
    private int moveCount;
    private char winner;
    private long revision;
    private long generation;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }
        this.board = new char[size][size];
        for (char[] row : board) {
            java.util.Arrays.fill(row, '.');
        }
        this.currentPlayer = 'X';
        this.moveCount = 0;
        this.winner = '.';
        this.revision = 0;
        this.generation = 0;
    }

    public Result makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Invalid player");
        }
        if (row < 0 || row >= board.length || column < 0 || column >= board[row].length) {
            throw new IllegalArgumentException("Out-of-bounds coordinates");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative revision");
        }

        synchronized (this) {
            if (expectedRevision != revision) {
                return new Result(Status.STALE_REVISION, revision, generation, winner);
            }
            if (winner != '.') {
                return new Result(Status.FINISHED, revision, generation, winner);
            }
            if (currentPlayer != player) {
                return new Result(Status.WRONG_TURN, revision, generation, winner);
            }
            if (board[row][column] != '.') {
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
    }

    private boolean checkWin(char player, int row, int column) {
        for (int dRow = -1; dRow <= 1; dRow++) {
            for (int dCol = -1; dCol <= 1; dCol++) {
                if (dRow == 0 && dCol == 0) continue;
                if (countStones(player, row, column, dRow, dCol) >= 5) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countStones(char player, int row, int column, int dRow, int dCol) {
        int count = 1;
        for (int i = 1; i < 5; i++) {
            int r = row + dRow * i;
            int c = column + dCol * i;
            if (r < 0 || r >= board.length || c < 0 || c >= board[r].length) break;
            if (board[r][c] != player) break;
            count++;
        }
        for (int i = -1; i >= -5; i--) {
            int r = row + dRow * i;
            int c = column + dCol * i;
            if (r < 0 || r >= board.length || c < 0 || c >= board[r].length) break;
            if (board[r][c] != player) break;
            count++;
        }
        return count - 1; // Subtract the original stone
    }

    public GomokuVersionState reset() {
        synchronized (this) {
            for (char[] row : board) {
                java.util.Arrays.fill(row, '.');
            }
            currentPlayer = 'X';
            moveCount = 0;
            winner = '.';
            revision++;
            generation++;

            return new GomokuVersionState(board.length);
        }
    }

    public Result snapshot() {
        synchronized (this) {
            char[][] copiedBoard = new char[board.length][];
            for (int i = 0; i < board.length; i++) {
                copiedBoard[i] = board[i].clone();
            }
            return new Result(Status.SUCCESS, revision, generation, winner, copiedBoard, moveCount, currentPlayer);
        }
    }

    public enum Status {
        SUCCESS,
        STALE_REVISION,
        FINISHED,
        WRONG_TURN,
        OCCUPIED
    }

    public static class Result {
        private final Status status;
        private final long revision;
        private final long generation;
        private final char winner;
        private final char[][] board;
        private final int moveCount;
        private final char currentPlayer;

        public Result(Status status, long revision, long generation, char winner) {
            this(status, revision, generation, winner, null, 0, '\0');
        }

        public Result(Status status, long revision, long generation, char winner, char[][] board, int moveCount, char currentPlayer) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.board = board;
            this.moveCount = moveCount;
            this.currentPlayer = currentPlayer;
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

        public char[][] getBoard() {
            return board;
        }

        public int getMoveCount() {
            return moveCount;
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }
    }
}
