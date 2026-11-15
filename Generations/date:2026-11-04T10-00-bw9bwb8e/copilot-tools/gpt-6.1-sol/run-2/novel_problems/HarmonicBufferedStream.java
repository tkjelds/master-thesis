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
        OPEN, READING, COMPLETING, CLOSED, FAILED
    }

    public static final class Result {
        private final byte[] bytes;
        private final long byteCount;
        private final int phase;
        private final Throwable failure;

        private Result(byte[] bytes, long byteCount, int phase,
                       Throwable failure) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.failure = failure;
        }

        public byte[] getBytes() {
            return copy(bytes);
        }

        public long getByteCount() {
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
        private final byte[] bytes;
        private final long byteCount;
        private final int phase;
        private final State state;

        private Snapshot(byte[] bytes, long byteCount, int phase, State state) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }

        public byte[] getBytes() {
            return copy(bytes);
        }

        public long getByteCount() {
            return byteCount;
        }

        public int getPhase() {
            return phase;
        }

        public State getState() {
            return state;
        }
    }

    private final Object monitor = new Object();

    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[] buffer = new byte[0];
    private long byteCount;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source,
                                  CompletionListener listener) {
        if (source == null || listener == null) {
            throw new NullPointerException("Source and listener are required");
        }
        this.source = source;
        this.listener = listener;
    }

    /*
     * Readers wait interruptibly only during READING. COMPLETING and terminal
     * states return null immediately, including calls made by callbacks.
     */
    public byte[] readChunk() throws InterruptedException {
        ChunkSource activeSource;

        synchronized (monitor) {
            while (state == State.READING) {
                monitor.wait();
            }

            if (state != State.OPEN) {
                return null;
            }

            state = State.READING;
            activeSource = source;
        }

        byte[] chunk = null;
        Throwable failure = null;

        try {
            byte[] incoming = activeSource.read();
            if (incoming != null) {
                chunk = copy(incoming);
            }
        } catch (RuntimeException | Error ex) {
            failure = ex;
        }

        boolean complete;

        synchronized (monitor) {
            if (failure == null && chunk != null) {
                try {
                    append(chunk);
                } catch (RuntimeException | Error ex) {
                    failure = ex;
                }
            }

            complete = failure != null || chunk == null || closeRequested;
            state = complete ? State.COMPLETING : State.OPEN;
            monitor.notifyAll();
        }

        if (complete) {
            finishCompletion(failure);
        }

        return chunk;
    }

    public void close() {
        synchronized (monitor) {
            if (state == State.COMPLETING
                    || state == State.CLOSED
                    || state == State.FAILED) {
                return;
            }

            closeRequested = true;

            if (state == State.READING) {
                return;
            }

            state = State.COMPLETING;
            monitor.notifyAll();
        }

        finishCompletion(null);
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(copy(buffer), byteCount, phase, state);
        }
    }

    /*
     * Called with the monitor held. Array-addressability exhaustion fails
     * explicitly rather than blocking or allowing the byte count to wrap.
     */
    private void append(byte[] chunk) {
        if (chunk.length > 2147483647 - buffer.length) {
            throw new IllegalStateException("Buffer exceeds maximum array size");
        }

        byte[] next = new byte[buffer.length + chunk.length];

        for (int i = 0; i < buffer.length; i++) {
            next[i] = buffer[i];
        }

        int nextPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            byte value = chunk[i];
            next[buffer.length + i] = value;
            nextPhase = (nextPhase + (value & 255)) % 360;
        }

        buffer = next;
        byteCount += chunk.length;
        phase = nextPhase;
    }

    private void finishCompletion(Throwable initialFailure) {
        ChunkSource completingSource;
        CompletionListener completingListener;
        byte[] completedBuffer;
        long completedCount;
        int completedPhase;

        synchronized (monitor) {
            completingSource = source;
            completingListener = listener;
            completedBuffer = buffer;
            completedCount = byteCount;
            completedPhase = phase;
        }

        Throwable failure = initialFailure;
        boolean callbacksFinished = false;

        try {
            try {
                completingSource.close();
            } catch (RuntimeException | Error ex) {
                failure = combine(failure, ex);
            }

            try {
                Result result = new Result(
                        copy(completedBuffer),
                        completedCount,
                        completedPhase,
                        failure);
                completingListener.complete(result);
            } catch (RuntimeException | Error ex) {
                failure = combine(failure, ex);
            }

            callbacksFinished = true;
        } finally {
            synchronized (monitor) {
                source = null;
                listener = null;
                state = callbacksFinished && failure == null
                        ? State.CLOSED : State.FAILED;
                monitor.notifyAll();
            }
        }

        rethrow(failure);
    }

    private static Throwable combine(Throwable earlier, Throwable later) {
        if (earlier == null) {
            return later;
        }
        if (earlier != later) {
            earlier.addSuppressed(later);
        }
        return earlier;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    private static byte[] copy(byte[] original) {
        byte[] detached = new byte[original.length];
        for (int i = 0; i < original.length; i++) {
            detached[i] = original[i];
        }
        return detached;
    }
}
