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

    public static final class Snapshot {
        private final byte[] bytes;
        private final long byteCount;
        private final int phase;
        private final State state;

        private Snapshot(byte[] bytes, long byteCount, int phase, State state) {
            this.bytes = copy(bytes);
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

    public static final class Result {
        private final byte[] bytes;
        private final long byteCount;
        private final int phase;
        private final Throwable failure;

        private Result(byte[] bytes, long byteCount, int phase, Throwable failure) {
            this.bytes = copy(bytes);
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

    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[] buffer = new byte[0];
    private long byteCount;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null || listener == null) {
            throw new NullPointerException();
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        ChunkSource activeSource;

        synchronized (this) {
            while (state == State.READING) {
                wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            activeSource = source;
        }

        byte[] retainedChunk = null;
        byte[] returnedChunk = null;
        Throwable failure = null;

        try {
            byte[] chunk = activeSource.read();
            if (chunk != null) {
                retainedChunk = copy(chunk);
                returnedChunk = copy(retainedChunk);
            }
        } catch (Throwable t) {
            failure = t;
        }

        boolean complete;
        synchronized (this) {
            if (failure == null && retainedChunk != null) {
                try {
                    append(retainedChunk);
                } catch (Throwable t) {
                    failure = t;
                }
            }

            complete = failure != null || retainedChunk == null || closeRequested;
            state = complete ? State.COMPLETING : State.OPEN;
            notifyAll();
        }

        if (complete) {
            finishCompletion(failure);
        }
        return retainedChunk == null ? null : returnedChunk;
    }

    public void close() {
        synchronized (this) {
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state != State.OPEN) {
                return;
            }
            closeRequested = true;
            state = State.COMPLETING;
            notifyAll();
        }

        finishCompletion(null);
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(buffer, byteCount, phase, state);
        }
    }

    // Called only while holding this object's monitor.
    private void append(byte[] chunk) {
        int oldLength = buffer.length;
        if (chunk.length > 2147483647 - oldLength) {
            throw new IllegalStateException("Buffered stream is too large");
        }

        byte[] updated = new byte[oldLength + chunk.length];
        for (int i = 0; i < oldLength; i++) {
            updated[i] = buffer[i];
        }

        int updatedPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            byte value = chunk[i];
            updated[oldLength + i] = value;
            updatedPhase = (updatedPhase + (value & 255)) % 360;
        }

        buffer = updated;
        byteCount = updated.length;
        phase = updatedPhase;
    }

    private void finishCompletion(Throwable initialFailure) {
        ChunkSource completingSource;
        CompletionListener completingListener;
        byte[] completedBuffer;
        long completedCount;
        int completedPhase;

        synchronized (this) {
            completingSource = source;
            completingListener = listener;
            completedBuffer = buffer;
            completedCount = byteCount;
            completedPhase = phase;
        }

        Throwable failure = initialFailure;
        try {
            try {
                completingSource.close();
            } catch (Throwable t) {
                failure = combine(failure, t);
            }

            try {
                Result result = new Result(
                        completedBuffer, completedCount, completedPhase, failure);
                completingListener.complete(result);
            } catch (Throwable t) {
                failure = combine(failure, t);
            }
        } finally {
            synchronized (this) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                notifyAll();
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

    private static byte[] copy(byte[] original) {
        byte[] result = new byte[original.length];
        for (int i = 0; i < original.length; i++) {
            result[i] = original[i];
        }
        return result;
    }
}
