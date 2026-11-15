@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
    @interface ThreadSafe {}

    public enum MoveStatus {
        SUCCESS,
        STALE_REVISION,
        FINISHED,
        WRONG_TURN,
        OCCUPIED
    }

    public static final class MoveResult {
        public final MoveStatus status;
        public final long revision;
        public final long generation;
        public final char winner;
        public final boolean draw;

        private MoveResult(MoveStatus status, long revision, long generation,
                           char winner, boolean draw) {
            this.status = status;
            this.revision = revision;
            this.generation = generation;
            this.winner = winner;
            this.draw = draw;
        }

        public MoveStatus getStatus() {
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

        public boolean isDraw() {
            return draw;
        }
    }

    public static final class Snapshot {
        public final int size;
        public final char[][] board;
        public final char currentPlayer;
        public final long moveCount;
        public final char winner;
        public final boolean draw;
        public final long generation;
        public final long revision;
        public final String version;

        private Snapshot(int size, char[][] board, char currentPlayer,
                         long moveCount, char winner, boolean draw,
                         long generation, long revision, String version) {
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

        public long getGeneration() {
            return generation;
        }

        public long getRevision() {
            return revision;
        }

        public String getVersion() {
            return version;
        }

        private static char[][] copyBoard(char[][] source) {
            char[][] copy = new char[source.length][];
            for (int row = 0; row < source.length; row++) {
                copy[row] = source[row].clone();
            }
            return copy;
        }
    }

    private final int size;
    private final char[][] board;
    private char currentPlayer;
    private long moveCount;
    private char winner;
    private boolean draw;
    private long generation;
    private long revision;
    private String version;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }

        this.size = size;
        this.board = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                board[row][column] = '.';
            }
        }

        currentPlayer = 'X';
        moveCount = 0;
        winner = '.';
        draw = false;
        generation = 0;
        revision = 0;
        version = buildVersion();
    }

    public MoveResult makeMove(char player, int row, int column,
                               long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Player must be X or O");
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Move is outside the board");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Revision cannot be negative");
        }

        synchronized (this) {
            if (expectedRevision != revision) {
                return result(MoveStatus.STALE_REVISION);
            }
            if (winner != '.' || draw) {
                return result(MoveStatus.FINISHED);
            }
            if (player != currentPlayer) {
                return result(MoveStatus.WRONG_TURN);
            }
            if (board[row][column] != '.') {
                return result(MoveStatus.OCCUPIED);
            }
            if (revision == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision overflow");
            }

            boolean wins = makesFive(row, column, player);
            long nextMoveCount = moveCount + 1;
            boolean endsInDraw = !wins
                    && nextMoveCount == (long) size * (long) size;

            board[row][column] = player;
            moveCount = nextMoveCount;
            if (wins) {
                winner = player;
            } else if (endsInDraw) {
                draw = true;
            } else {
                currentPlayer = player == 'X' ? 'O' : 'X';
            }
            revision++;
            version = buildVersion();

            return result(MoveStatus.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (this) {
            if (revision == Long.MAX_VALUE || generation == Long.MAX_VALUE) {
                throw new ArithmeticException("Revision or generation overflow");
            }

            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    board[row][column] = '.';
                }
            }

            currentPlayer = 'X';
            moveCount = 0;
            winner = '.';
            draw = false;
            generation++;
            revision++;
            version = buildVersion();

            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private boolean makesFive(int row, int column, char player) {
        return 1 + countDirection(row, column, 0, 1, player)
                + countDirection(row, column, 0, -1, player) >= 5
                || 1 + countDirection(row, column, 1, 0, player)
                + countDirection(row, column, -1, 0, player) >= 5
                || 1 + countDirection(row, column, 1, 1, player)
                + countDirection(row, column, -1, -1, player) >= 5
                || 1 + countDirection(row, column, 1, -1, player)
                + countDirection(row, column, -1, 1, player) >= 5;
    }

    private int countDirection(int row, int column, int rowStep,
                               int columnStep, char player) {
        int count = 0;
        int nextRow = row + rowStep;
        int nextColumn = column + columnStep;
        while (nextRow >= 0 && nextRow < size
                && nextColumn >= 0 && nextColumn < size
                && board[nextRow][nextColumn] == player) {
            count++;
            nextRow += rowStep;
            nextColumn += columnStep;
        }
        return count;
    }

    private MoveResult result(MoveStatus status) {
        return new MoveResult(status, revision, generation, winner, draw);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(size, board, currentPlayer, moveCount, winner,
                draw, generation, revision, version);
    }

    private String buildVersion() {
        String value = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            value += ".WIN_X";
        } else if (winner == 'O') {
            value += ".WIN_O";
        } else if (draw) {
            value += ".DRAW";
        }
        return value;
    }
}
