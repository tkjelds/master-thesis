import java.lang.*;

@GomokuVersionState.ThreadSafe
public class GomokuVersionState {
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

        /** Returns 'X', 'O', or '.' when there is no winner. */
        public char getWinner() {
            return winner;
        }
    }

    public static final class Snapshot {
        private final char[][] board;
        private final char currentPlayer;
        private final char winner;
        private final boolean draw;
        private final int moveCount;
        private final long generation;
        private final long revision;
        private final String version;

        private Snapshot(char[][] board, char currentPlayer, char winner, boolean draw,
                         int moveCount, long generation, long revision, String version) {
            this.board = board;
            this.currentPlayer = currentPlayer;
            this.winner = winner;
            this.draw = draw;
            this.moveCount = moveCount;
            this.generation = generation;
            this.revision = revision;
            this.version = version;
        }

        public char[][] getBoard() {
            char[][] copy = new char[board.length][];
            for (int i = 0; i < board.length; i++) {
                copy[i] = board[i].clone();
            }
            return copy;
        }

        public char getCurrentPlayer() {
            return currentPlayer;
        }

        public char getWinner() {
            return winner;
        }

        public boolean isDraw() {
            return draw;
        }

        public boolean isFinished() {
            return draw || winner != '.';
        }

        public int getMoveCount() {
            return moveCount;
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

    private static final char EMPTY = '.';

    private final Object lock = new Object();
    private final int size;

    // All fields below guarded by lock.
    private final char[][] board;
    private char currentPlayer;
    private char winner;
    private boolean draw;
    private int moveCount;
    private long generation;
    private long revision;
    private String version;

    public GomokuVersionState(int size) {
        if (size < 5) {
            throw new IllegalArgumentException("Board size must be at least 5");
        }
        this.size = size;
        this.board = new char[size][size];
        synchronized (lock) {
            clearBoardLocked();
            currentPlayer = 'X';
            winner = EMPTY;
            draw = false;
            moveCount = 0;
            generation = 0;
            revision = 0;
            version = buildVersionLocked();
        }
    }

    private void clearBoardLocked() {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                board[i][j] = EMPTY;
            }
        }
    }

    private String buildVersionLocked() {
        String v = size + "." + moveCount + "." + (int) currentPlayer;
        if (winner == 'X') {
            v += ".WIN_X";
        } else if (winner == 'O') {
            v += ".WIN_O";
        } else if (draw) {
            v += ".DRAW";
        }
        return v;
    }

    private Snapshot snapshotLocked() {
        char[][] copy = new char[size][];
        for (int i = 0; i < size; i++) {
            copy[i] = board[i].clone();
        }
        return new Snapshot(copy, currentPlayer, winner, draw, moveCount, generation, revision, version);
    }

    private MoveResult resultLocked(Status status) {
        return new MoveResult(status, revision, generation, winner);
    }

    private int countLocked(int r, int c, int dr, int dc, char p) {
        int n = 0;
        int i = r + dr;
        int j = c + dc;
        while (i >= 0 && i < size && j >= 0 && j < size && board[i][j] == p) {
            n++;
            i += dr;
            j += dc;
        }
        return n;
    }

    private boolean winsLocked(int r, int c, char p) {
        int[][] dirs = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            int total = 1 + countLocked(r, c, d[0], d[1], p) + countLocked(r, c, -d[0], -d[1], p);
            if (total >= 5) {
                return true;
            }
        }
        return false;
    }

    public MoveResult makeMove(char player, int row, int column, long expectedRevision) {
        if (player != 'X' && player != 'O') {
            throw new IllegalArgumentException("Invalid player: " + player);
        }
        if (row < 0 || row >= size || column < 0 || column >= size) {
            throw new IllegalArgumentException("Coordinates out of bounds");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative revision");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return resultLocked(Status.STALE_REVISION);
            }
            if (winner != EMPTY || draw) {
                return resultLocked(Status.FINISHED);
            }
            if (player != currentPlayer) {
                return resultLocked(Status.WRONG_TURN);
            }
            if (board[row][column] != EMPTY) {
                return resultLocked(Status.OCCUPIED);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }
            board[row][column] = player;
            moveCount++;
            revision++;
            if (winsLocked(row, column, player)) {
                winner = player;
            } else if (moveCount == size * size) {
                draw = true;
            } else {
                currentPlayer = (player == 'X') ? 'O' : 'X';
            }
            version = buildVersionLocked();
            return resultLocked(Status.SUCCESS);
        }
    }

    public Snapshot reset() {
        synchronized (lock) {
            if (revision == Long.MAX_VALUE || generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision or generation overflow");
            }
            clearBoardLocked();
            currentPlayer = 'X';
            winner = EMPTY;
            draw = false;
            moveCount = 0;
            generation++;
            revision++;
            version = buildVersionLocked();
            return snapshotLocked();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }
}
