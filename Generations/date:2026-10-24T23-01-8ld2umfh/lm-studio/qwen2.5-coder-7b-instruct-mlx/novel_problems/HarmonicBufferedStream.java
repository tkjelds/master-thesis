@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private static final byte[] EMPTY_CHUNK = new byte[0];

    interface ChunkSource {
        byte[] read();
        void close();
    }

    interface CompletionListener {
        void complete(Result result);
    }

    static class Result {
        private final byte[] bytes;
        private final int count;
        private final int phase;
        private final Throwable error;

        Result(byte[] bytes, int count, int phase, Throwable error) {
            this.bytes = bytes;
            this.count = count;
            this.phase = phase;
            this.error = error;
        }

        byte[] getBytes() {
            return bytes;
        }

        int getCount() {
            return count;
        }

        int getPhase() {
            return phase;
        }

        Throwable getError() {
            return error;
        }
    }

    private final ChunkSource source;
    private final CompletionListener listener;
    private final ByteBuffer buffer;
    private State state;
    private byte[] currentChunk;
    private int byteCount;
    private int phase;
    private Throwable error;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
        this.buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
        this.state = State.OPEN;
        this.currentChunk = null;
        this.byteCount = 0;
        this.phase = 0;
    }

    public synchronized byte[] readChunk() throws InterruptedException {
        while (state == State.READING) {
            wait();
        }
        if (state == State.CLOSED || state == State.FAILED) {
            throw new IllegalStateException();
        }
        if (currentChunk != null) {
            byte[] chunk = currentChunk;
            currentChunk = null;
            notifyAll();
            return chunk;
        }
        source.read();
        while (state == State.READING) {
            wait();
        }
        if (state == State.CLOSED || state == State.FAILED) {
            throw new IllegalStateException();
        }
        return currentChunk != null ? currentChunk : EMPTY_CHUNK;
    }

    public synchronized void close() {
        if (state == State.CLOSED || state == State.FAILED) {
            return;
        }
        if (state != State.OPEN && state != State.READING) {
            completeState(State.CLOSED);
        } else {
            synchronized (this) {
                state = State.COMPLETING;
                notifyAll();
            }
            source.close();
            completeState(State.CLOSED);
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(buffer.array(), byteCount, phase, state);
    }

    private synchronized void completeState(State newState) {
        if (state.compareAndSet(newState, newState)) {
            state = newState;
            notifyAll();
        }
    }

    private synchronized void readChunkInternal() {
        if (state == State.READING) {
            return;
        }
        state = State.READING;
        notifyAll();
    }

    private synchronized void completeChunk(byte[] chunk, int count) {
        if (chunk != null) {
            currentChunk = new byte[count];
            System.arraycopy(chunk, 0, currentChunk, 0, count);
        }
        state = State.OPEN;
        notifyAll();
    }

    private synchronized void completeWithError(Throwable error) {
        this.error = error;
        state = State.FAILED;
        notifyAll();
    }

    private enum State {
        OPEN, READING, COMPLETING, CLOSED, FAILED
    }

    static class Snapshot {
        private final byte[] bytes;
        private final int count;
        private final int phase;
        private final State state;

        Snapshot(byte[] bytes, int count, int phase, State state) {
            this.bytes = bytes;
            this.count = count;
            this.phase = phase;
            this.state = state;
        }

        byte[] getBytes() {
            return bytes;
        }

        int getCount() {
            return count;
        }

        int getPhase() {
            return phase;
        }

        State getState() {
            return state;
        }
    }
}
