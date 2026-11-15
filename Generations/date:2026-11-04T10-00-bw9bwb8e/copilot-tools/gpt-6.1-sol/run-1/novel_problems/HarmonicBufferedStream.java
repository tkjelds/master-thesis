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
        public final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final Throwable failure;

        private Result(byte[] bytes, long byteCount, int phase,
                       Throwable failure) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.failure = failure;
        }
    }

    public static final class Snapshot {
        public final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final State state;

        private Snapshot(byte[] bytes, long byteCount, int phase,
                         State state) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }
    }

    private final Object monitor = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[] buffer = new byte[0];
    private int byteCount;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source,
                                  CompletionListener listener) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.source = source;
        this.listener = listener;
    }

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
            byte[] supplied = activeSource.read();
            if (supplied != null) {
                chunk = copy(supplied, supplied.length);
            }
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }

        boolean mustComplete;
        synchronized (monitor) {
            if (failure == null && chunk != null) {
                try {
                    append(chunk);
                } catch (RuntimeException | Error exception) {
                    failure = exception;
                }
            }

            mustComplete = failure != null || chunk == null || closeRequested;
            state = mustComplete ? State.COMPLETING : State.OPEN;
            monitor.notifyAll();
        }

        if (mustComplete) {
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
            return new Snapshot(
                    copy(buffer, byteCount), byteCount, phase, state);
        }
    }

    // Called only while holding monitor; commit metadata after copying.
    private void append(byte[] chunk) {
        if (chunk.length > Integer.MAX_VALUE - byteCount) {
            throw new IllegalStateException("Buffer exceeds maximum array size");
        }

        int required = byteCount + chunk.length;
        byte[] destination = buffer;

        if (required > buffer.length) {
            int capacity = buffer.length == 0 ? 16 : buffer.length;
            while (capacity < required) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = Integer.MAX_VALUE;
                } else {
                    capacity *= 2;
                }
            }
            destination = copy(buffer, capacity);
        }

        int nextPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            destination[byteCount + i] = chunk[i];
            nextPhase = (nextPhase + (chunk[i] & 255)) % 360;
        }

        buffer = destination;
        byteCount = required;
        phase = nextPhase;
    }

    // Exactly one caller owns completion after publishing COMPLETING.
    private void finishCompletion(Throwable initialFailure) {
        ChunkSource completingSource;
        CompletionListener completingListener;
        synchronized (monitor) {
            completingSource = source;
            completingListener = listener;
        }

        Throwable failure = initialFailure;
        boolean finished = false;
        try {
            try {
                completingSource.close();
            } catch (RuntimeException | Error exception) {
                failure = combine(failure, exception);
            }

            Result result;
            synchronized (monitor) {
                result = new Result(
                        copy(buffer, byteCount), byteCount, phase, failure);
            }

            try {
                completingListener.complete(result);
            } catch (RuntimeException | Error exception) {
                failure = combine(failure, exception);
            }
            finished = true;
        } finally {
            synchronized (monitor) {
                source = null;
                listener = null;
                state = finished && failure == null
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
        if (failure != null) {
            throw (Error) failure;
        }
    }

    private static byte[] copy(byte[] original, int length) {
        byte[] detached = new byte[length];
        int copied = original.length < length ? original.length : length;
        for (int i = 0; i < copied; i++) {
            detached[i] = original[i];
        }
        return detached;
    }
}
