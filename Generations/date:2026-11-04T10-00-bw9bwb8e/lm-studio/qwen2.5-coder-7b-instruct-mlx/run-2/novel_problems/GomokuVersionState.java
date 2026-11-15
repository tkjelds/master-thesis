@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}

    private final int size;
    private final char[][] board;
    private char currentPlayer;
    private int moveCount;
    private char winner;
    private long generation;
    private long revision;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }
        this.size = size;
        board = new char[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                board[i][j] = '.';
            }
        }
        currentPlayer = 'X';
        moveCount = 0;
        winner = '.';
        generation = 0;
        revision = 0;
    }

    public Result makeMove(char player, int row, int column, long expectedRevision) {
        synchronized (this) {
            if (player != currentPlayer || revision < expectedRevision) {
                return new Result(Status.STALE_REVISION, revision, generation, winner);
            }
            if (player != 'X' && player != 'O') {
                throw new IllegalArgumentException("Invalid player");
            }
            if (row < 0 || row >= size || column < 0 || column >= size) {
                throw new IllegalArgumentException("Out-of-bounds coordinates");
            }
            if (board[row][column] != '.') {
                return new Result(Status.OCCUPIED, revision, generation, winner);
            }
            board[row][column] = player;
            moveCount++;
            if (checkWin(player, row, column)) {
                winner = player;
            } else if (moveCount == size * size) {
                winner = 'D';
            }
            revision++;
            return new Result(Status.SUCCESS, revision, generation, winner);
        }
    }

    public void reset() {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    board[i][j] = '.';
                }
            }
            currentPlayer = 'X';
            moveCount = 0;
            winner = '.';
            generation++;
            revision++;
        }
    }

    public Result snapshot() {
        synchronized (this) {
            char[][] copy = new char[size][size];
            for (int i = 0; i < size; i++) {
                System.arraycopy(board, i, copy[i], 0, size);
            }
            return new Result(Status.SUCCESS, revision, generation, winner, copy, currentPlayer, moveCount);
        }
    }

    private boolean checkWin(char player, int row, int column) {
        return checkDirection(player, row, column, 1, 0) ||
               checkDirection(player, row, column, 0, 1) ||
               checkDirection(player, row, column, 1, 1) ||
               checkDirection(player, row, column, -1, 1);
    }

    private boolean checkDirection(char player, int row, int column, int dRow, int dCol) {
        int count = 1;
        for (int i = 1; i < size; i++) {
            if (row + dRow * i >= size || row + dRow * i < 0 || column + dCol * i >= size || column + dCol * i < 0) {
                break;
            }
            if (board[row + dRow * i][column + dCol * i] == player) {
                count++;
            } else {
                break;
            }
        }
        for (int i = 1; i < size; i++) {
            if (row - dRow * i >= size || row - dRow * i < 0 || column - dCol * i >= size || column - dCol * i < 0) {
                break;
            }
            if (board[row - dRow * i][column - dCol * i] == player) {
                count++;
            } else {
                break;
            }
        }
        return count >= 5;
    }

    public enum Status {
        SUCCESS, STALE_REVISION, WRONG_TURN, OCCUPIED
    }

    public static class Result {
        private final Status status;
        private final long revision;
        private final long generation;
        private char winner;
        private char[][] board;
        private char currentPlayer;
        private int moveCount;

        public Result(Status status, long revision, long generation) {
            this(status, revision, generation, '.', null, 'X', 0);
        }

        public Result(Status status, long revision, long generation, char winner) {
            this(status, revision, generation, winner, null, 'X', 0);
        }

        public Result(Status status, long revision, long generation, char winner, char[][] board, char currentPlayer, int moveCount) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.board = board;
            this.currentPlayer = currentPlayer;
            this.moveCount = moveCount;
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
            if (board == null) {
                return new char[0][];
            }
            char[][] copy = new char[size][size];
            for (int i = 0; i < size; i++) {
                System.arraycopy(board, i, copy[i], 0, size);
            }
            return copy;
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }

        public int getMoveCount() {
            return moveCount;
        }
    }
}
