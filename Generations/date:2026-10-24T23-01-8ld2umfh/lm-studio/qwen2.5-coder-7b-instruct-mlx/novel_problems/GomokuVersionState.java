@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}

    private static final char EMPTY = '.';
    private static final int MIN_SIZE = 5;
    private static final String WIN_X = ".WIN_X";
    private static final String WIN_O = ".WIN_O";
    private static final String DRAW = ".DRAW";

    private final int size;
    private char[][] board;
    private char currentPlayer;
    private int moveCount;
    private String winner;
    private long generation;
    private long revision;

    public GomokuVersionState(int size) {
        if (size < MIN_SIZE) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }
        this.size = size;
        board = new char[size][size];
        reset();
    }

    public synchronized Result makeMove(char player, int row, int column, long expectedRevision) {
        checkPlayer(player);
        checkBounds(row, column);
        if (revision != expectedRevision) {
            return new Result(Status.STALE_REVISION, revision, generation, winner);
        }
        if (winner != null) {
            return new Result(Status.FINISHED, revision, generation, winner);
        }
        if (board[row][column] != EMPTY) {
            return new Result(Status.OCCUPIED, revision, generation, winner);
        }
        board[row][column] = player;
        moveCount++;
        currentPlayer = (player == 'X') ? 'O' : 'X';
       revision++;
        return updateStatus();
    }

    private synchronized Result updateStatus() {
        if (moveCount == size * size) {
            winner = DRAW;
        } else {
            for (int i = 0; i < size; i++) {
                if (checkDirection(i, 0, 1, 0) || checkDirection(0, i, 0, 1)) {
                    winner = String.valueOf(currentPlayer) + WIN_X;
                }
                if (checkDirection(i, 0, 1, 1) || checkDirection(0, i, 1, 1)) {
                    winner = String.valueOf(currentPlayer) + WIN_O;
                }
            }
            if (winner == null) {
                winner = "";
            }
        }
        return new Result(Status.SUCCESS, revision, generation, winner);
    }

    private boolean checkDirection(int row, int column, int dRow, int dColumn) {
        for (int k = 0; k < size; k++) {
            if (!inBounds(row + (k - 1) * dRow, column + (k - 1) * dColumn)) {
                return false;
            }
        }
        for (int k = 1; k < 5; k++) {
            if (!inBounds(row + (k - 1) * dRow, column + (k - 1) * dColumn)
                    || board[row][column] != board[row + k * dRow][column + k * dColumn]) {
                return false;
            }
        }
        return true;
    }

    private boolean inBounds(int row, int column) {
        return row >= 0 && row < size && column >= 0 && column < size;
    }

    public synchronized GomokuVersionState reset() {
        board = new char[size][size];
        currentPlayer = 'X';
        moveCount = 0;
        winner = null;
        generation++;
        revision++;
        return new GomokuVersionState(size);
    }

    public synchronized Snapshot snapshot() {
        char[][] copy = new char[size][size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(board[i], 0, copy[i], 0, size);
        }
        return new Snapshot(copy, currentPlayer, moveCount, winner, generation, revision);
    }

    private void checkPlayer(char player) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Invalid player");
        }
    }

    private void checkBounds(int row, int column) {
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinate out of bounds");
        }
    }

    public static class Result {
        private final Status status;
        private final long revision;
        private final long generation;
        private final String winner;

        public Result(Status status, long revision, long generation, String winner) {
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

        public String getWinner() {
            return winner;
        }
    }

    public static class Snapshot {
        private final char[][] board;
        private final char currentPlayer;
        private final int moveCount;
        private final String winner;
        private final long generation;
        private final long revision;

        public Snapshot(char[][] board, char currentPlayer, int moveCount, String winner, long generation, long revision) {
            this.board = board;
            this.currentPlayer = currentPlayer;
            this.moveCount = moveCount;
            this.winner = winner;
            this.generation = generation;
            this.revision = revision;
        }

        public char[][] getBoard() {
            return board.clone();
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }

        public int getMoveCount() {
            return moveCount;
        }

        public String getWinner() {
            return winner;
        }

        public long getGeneration() {
            return generation;
        }

        public long getRevision() {
            return revision;
        }
    }

    public enum Status {
        SUCCESS, STALE_REVISION, FINISHED, WRONG_TURN, OCCUPIED
    }
}
