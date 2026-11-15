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
    private int size;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source,
                                  CompletionListener listener) {
        if (source == null || listener == null) {
            throw new NullPointerException("Source and listener are required");
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
        } catch (RuntimeException | Error problem) {
            failure = problem;
        }

        boolean complete;
        synchronized (monitor) {
            if (failure == null && chunk != null) {
                try {
                    append(chunk);
                } catch (RuntimeException | Error problem) {
                    failure = problem;
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
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state != State.OPEN) {
                return;
            }
            closeRequested = true;
            state = State.COMPLETING;
            monitor.notifyAll();
        }
        finishCompletion(null);
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(copy(buffer, size), size, phase, state);
        }
    }

    // Called only while holding monitor; returned chunks never alias buffer.
    private void append(byte[] chunk) {
        if (chunk.length > 2147483647 - size) {
            throw new IllegalStateException("Buffered bytes exceed array capacity");
        }
        int required = size + chunk.length;
        byte[] target = buffer;
        if (required > buffer.length) {
            int capacity = buffer.length == 0 ? 32 : buffer.length;
            while (capacity < required) {
                capacity = capacity <= 1073741823
                        ? capacity * 2 : 2147483647;
            }
            target = copy(buffer, capacity);
        }

        int updatedPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            target[size + i] = chunk[i];
            updatedPhase = (updatedPhase + (chunk[i] & 255)) % 360;
        }
        buffer = target;
        size = required;
        phase = updatedPhase;
    }

    // Only the thread that changes state to COMPLETING calls this method.
    private void finishCompletion(Throwable failure) {
        ChunkSource completingSource;
        CompletionListener completingListener;
        synchronized (monitor) {
            completingSource = source;
            completingListener = listener;
        }

        try {
            try {
                completingSource.close();
            } catch (RuntimeException | Error problem) {
                failure = combine(failure, problem);
            }

            Result result;
            synchronized (monitor) {
                result = new Result(copy(buffer, size), size, phase, failure);
            }
            try {
                completingListener.complete(result);
            } catch (RuntimeException | Error problem) {
                failure = combine(failure, problem);
            }
        } catch (RuntimeException | Error problem) {
            failure = combine(failure, problem);
        } finally {
            synchronized (monitor) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                monitor.notifyAll();
            }
        }

        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure != null) {
            throw (Error) failure;
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

    private static byte[] copy(byte[] original, int length) {
        byte[] detached = new byte[length];
        int copiedLength = original.length < length ? original.length : length;
        for (int i = 0; i < copiedLength; i++) {
            detached[i] = original[i];
        }
        return detached;
    }
}
