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

        private Result(byte[] bytes, long byteCount, int phase, Throwable failure) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.failure = failure;
        }

        public byte[] getBytes() {
            return bytes.clone();
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
            return bytes.clone();
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

    public HarmonicBufferedStream(
            ChunkSource source, CompletionListener listener) {
        if (source == null || listener == null) {
            throw new NullPointerException();
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        ChunkSource currentSource;

        synchronized (monitor) {
            while (state == State.READING) {
                monitor.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            currentSource = source;
        }

        byte[] chunk = null;
        Throwable failure = null;
        try {
            byte[] supplied = currentSource.read();
            if (supplied != null) {
                chunk = supplied.clone();
            }
        } catch (Throwable error) {
            failure = error;
        }

        boolean complete;
        synchronized (monitor) {
            if (failure == null && chunk != null) {
                try {
                    append(chunk);
                } catch (Throwable error) {
                    failure = error;
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
        boolean complete = false;

        synchronized (monitor) {
            if (state == State.READING) {
                closeRequested = true;
            } else if (state == State.OPEN) {
                closeRequested = true;
                state = State.COMPLETING;
                complete = true;
                monitor.notifyAll();
            }
        }

        if (complete) {
            finishCompletion(null);
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(buffer.clone(), byteCount, phase, state);
        }
    }

    // Called only while holding monitor.
    private void append(byte[] chunk) {
        if (chunk.length > 2147483647 - buffer.length) {
            throw new IllegalStateException("Buffered stream is too large");
        }

        byte[] combined = new byte[buffer.length + chunk.length];
        for (int i = 0; i < buffer.length; i++) {
            combined[i] = buffer[i];
        }

        int newPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            byte value = chunk[i];
            combined[buffer.length + i] = value;
            newPhase = (newPhase + (value & 255)) % 360;
        }

        buffer = combined;
        byteCount += chunk.length;
        phase = newPhase;
    }

    // The caller has already claimed completion by setting COMPLETING.
    private void finishCompletion(Throwable initialFailure) {
        Throwable failure = initialFailure;
        ChunkSource currentSource;
        CompletionListener currentListener;

        synchronized (monitor) {
            currentSource = source;
            currentListener = listener;
        }

        try {
            try {
                currentSource.close();
            } catch (Throwable error) {
                failure = combine(failure, error);
            }

            Result result;
            synchronized (monitor) {
                result = new Result(buffer.clone(), byteCount, phase, failure);
            }

            try {
                currentListener.complete(result);
            } catch (Throwable error) {
                failure = combine(failure, error);
            }
        } catch (Throwable error) {
            failure = combine(failure, error);
        } finally {
            synchronized (monitor) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                monitor.notifyAll();
            }
        }

        if (failure != null) {
            rethrow(failure);
        }
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
        throw new RuntimeException(failure);
    }
}
