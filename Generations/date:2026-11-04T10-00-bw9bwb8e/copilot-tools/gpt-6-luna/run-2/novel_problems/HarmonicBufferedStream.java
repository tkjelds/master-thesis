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
        private final byte[] buffer;
        private final long byteCount;
        private final int phase;
        private final State state;

        private Snapshot(byte[] buffer, long byteCount, int phase, State state) {
            this.buffer = buffer;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }

        public byte[] getBuffer() {
            return buffer.clone();
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
        private final byte[] buffer;
        private final long byteCount;
        private final int phase;
        private final Throwable readFailure;
        private final Throwable closeFailure;

        private Result(byte[] buffer, long byteCount, int phase,
                       Throwable readFailure, Throwable closeFailure) {
            this.buffer = buffer;
            this.byteCount = byteCount;
            this.phase = phase;
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        public byte[] getBuffer() {
            return buffer.clone();
        }

        public long getByteCount() {
            return byteCount;
        }

        public int getPhase() {
            return phase;
        }

        public Throwable getReadFailure() {
            return readFailure;
        }

        public Throwable getCloseFailure() {
            return closeFailure;
        }
    }

    private final Object lock = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[] buffer = new byte[0];
    private int bufferLength;
    private long byteCount;
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

    public byte[] readChunk() throws InterruptedException {
        ChunkSource activeSource;

        synchronized (lock) {
            while (true) {
                if (state == State.COMPLETING
                        || state == State.CLOSED
                        || state == State.FAILED) {
                    return null;
                }
                if (state == State.READING) {
                    lock.wait();
                    continue;
                }
                if (closeRequested) {
                    return null;
                }
                state = State.READING;
                activeSource = source;
                break;
            }
        }

        byte[] chunk = null;
        Throwable readFailure = null;
        try {
            byte[] sourceChunk = activeSource.read();
            if (sourceChunk != null) {
                chunk = sourceChunk.clone();
            }
        } catch (RuntimeException | Error failure) {
            readFailure = failure;
        }

        boolean complete;
        synchronized (lock) {
            if (readFailure == null && chunk != null) {
                try {
                    appendChunk(chunk);
                } catch (RuntimeException | Error failure) {
                    readFailure = failure;
                }
            }

            complete = readFailure != null || chunk == null || closeRequested;
            if (complete) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            lock.notifyAll();
        }

        if (complete) {
            complete(readFailure);
        }
        return chunk == null ? null : chunk.clone();
    }

    public void close() {
        boolean complete = false;

        synchronized (lock) {
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
            lock.notifyAll();
            complete = true;
        }

        if (complete) {
            complete(null);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copyBuffer(), byteCount, phase, state);
        }
    }

    private void appendChunk(byte[] chunk) {
        long requiredLong = (long) bufferLength + chunk.length;
        if (requiredLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("Buffered data exceeds the maximum array size");
        }

        int required = (int) requiredLong;
        byte[] target = buffer;
        if (required > buffer.length) {
            int capacity = buffer.length == 0 ? 16 : buffer.length;
            while (capacity < required) {
                long doubled = (long) capacity * 2;
                capacity = (int) Math.min(doubled, Integer.MAX_VALUE);
            }
            target = new byte[capacity];
            System.arraycopy(buffer, 0, target, 0, bufferLength);
        }

        System.arraycopy(chunk, 0, target, bufferLength, chunk.length);
        buffer = target;
        bufferLength = required;
        byteCount += chunk.length;

        for (byte value : chunk) {
            phase = (phase + (value & 0xff)) % 360;
        }
    }

    private byte[] copyBuffer() {
        byte[] copy = new byte[bufferLength];
        System.arraycopy(buffer, 0, copy, 0, bufferLength);
        return copy;
    }

    private void complete(Throwable readFailure) {
        ChunkSource completionSource;
        CompletionListener completionListener;
        synchronized (lock) {
            completionSource = source;
            completionListener = listener;
        }

        Throwable failure = readFailure;
        Throwable closeFailure = null;
        try {
            completionSource.close();
        } catch (RuntimeException | Error error) {
            closeFailure = error;
            failure = combine(failure, error);
        }

        Result result;
        synchronized (lock) {
            result = new Result(copyBuffer(), byteCount, phase, readFailure, closeFailure);
        }

        try {
            completionListener.complete(result);
        } catch (RuntimeException | Error error) {
            failure = combine(failure, error);
        }

        synchronized (lock) {
            source = null;
            listener = null;
            state = failure == null ? State.CLOSED : State.FAILED;
            lock.notifyAll();
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
}
