@HarmonicBufferedStream.ThreadSafe
public final class HarmonicBufferedStream {
    @interface ThreadSafe {}


    public interface ChunkSource {
        byte[] read();

        void close();
    }

    public interface CompletionListener {
        void complete(Result result);
    }

    public enum State {
        OPEN,
        READING,
        COMPLETING,
        CLOSED,
        FAILED
    }

    public static final class Result {
        private final byte[] bufferedBytes;
        private final int byteCount;
        private final int phase;
        private final Throwable failure;

        private Result(byte[] source, int length, int phase, Throwable failure) {
            this.bufferedBytes = new byte[length];
            System.arraycopy(source, 0, this.bufferedBytes, 0, length);
            this.byteCount = length;
            this.phase = phase;
            this.failure = failure;
        }

        public byte[] getBufferedBytes() {
            byte[] copy = new byte[bufferedBytes.length];
            System.arraycopy(bufferedBytes, 0, copy, 0, bufferedBytes.length);
            return copy;
        }

        public int getByteCount() {
            return byteCount;
        }

        public int getPhase() {
            return phase;
        }

        public Throwable getFailure() {
            return failure;
        }
    }

    public static final class Snapshot {
        private final byte[] bufferedBytes;
        private final int byteCount;
        private final int phase;
        private final State state;

        private Snapshot(byte[] source, int length, int phase, State state) {
            this.bufferedBytes = new byte[length];
            System.arraycopy(source, 0, this.bufferedBytes, 0, length);
            this.byteCount = length;
            this.phase = phase;
            this.state = state;
        }

        public byte[] getBufferedBytes() {
            byte[] copy = new byte[bufferedBytes.length];
            System.arraycopy(bufferedBytes, 0, copy, 0, bufferedBytes.length);
            return copy;
        }

        public int getByteCount() {
            return byteCount;
        }

        public int getPhase() {
            return phase;
        }

        public State getState() {
            return state;
        }
    }

    // All fields below are guarded by 'lock'.
    private final Object lock = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private Throwable firstError;
    private byte[] data = new byte[64];
    private int size;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.source = source;
        this.listener = listener;
    }

    /**
     * Returns a detached chunk, or null after completion (including when
     * completion is in progress). Waits interruptibly while another read is in flight.
     */
    public byte[] readChunk() throws InterruptedException {
        ChunkSource src;
        synchronized (lock) {
            while (state == State.READING) {
                lock.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            src = source;
        }

        byte[] chunk = null;
        Throwable failure = null;
        try {
            chunk = src.read();
            if (chunk != null) {
                chunk = chunk.clone();
            }
        } catch (Throwable t) {
            failure = t;
            chunk = null;
        }

        boolean finish;
        synchronized (lock) {
            if (failure != null) {
                addErrorLocked(failure);
                finish = true;
            } else if (chunk == null) {
                finish = true;
            } else {
                appendLocked(chunk);
                finish = closeRequested;
            }
            if (finish) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            lock.notifyAll();
        }

        if (finish) {
            completeClaimed();
            return null;
        }
        return chunk;
    }

    /**
     * Requests close. Nonblocking if a read is in flight; otherwise completes
     * the stream on the calling thread. No-op during COMPLETING or terminal states.
     */
    public void close() {
        synchronized (lock) {
            if (state == State.OPEN) {
                state = State.COMPLETING;
                lock.notifyAll();
            } else if (state == State.READING) {
                closeRequested = true;
                return;
            } else {
                return;
            }
        }
        completeClaimed();
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(data, size, phase, state);
        }
    }

    /**
     * Runs completion. Caller must have already moved state to COMPLETING,
     * which grants exclusive completion ownership.
     */
    private void completeClaimed() {
        ChunkSource src;
        CompletionListener lst;
        synchronized (lock) {
            src = source;
            lst = listener;
        }

        try {
            try {
                src.close();
            } catch (Throwable t) {
                synchronized (lock) {
                    addErrorLocked(t);
                }
            }

            try {
                Result result;
                synchronized (lock) {
                    result = new Result(data, size, phase, firstError);
                }
                lst.complete(result);
            } catch (Throwable t) {
                synchronized (lock) {
                    addErrorLocked(t);
                }
            }
        } finally {
            synchronized (lock) {
                state = (firstError == null) ? State.CLOSED : State.FAILED;
                source = null;
                listener = null;
                lock.notifyAll();
            }
        }

        Throwable err;
        synchronized (lock) {
            err = firstError;
        }
        rethrow(err);
    }

    /** Must be called with lock held. */
    private void addErrorLocked(Throwable t) {
        if (t == null) {
            return;
        }
        if (firstError == null) {
            firstError = t;
        } else if (firstError != t) {
            firstError.addSuppressed(t);
        }
    }

    /** Must be called with lock held. */
    private void appendLocked(byte[] chunk) {
        int needed = size + chunk.length;
        if (needed > data.length) {
            int newCap = Math.max(data.length * 2, needed);
            byte[] grown = new byte[newCap];
            System.arraycopy(data, 0, grown, 0, size);
            data = grown;
        }
        System.arraycopy(chunk, 0, data, size, chunk.length);
        size += chunk.length;
        int p = phase;
        for (int i = 0; i < chunk.length; i++) {
            p = (p + (chunk[i] & 0xFF)) % 360;
        }
        phase = p;
    }

    private static void rethrow(Throwable t) {
        if (t == null) {
            return;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new IllegalStateException(t);
    }
}
