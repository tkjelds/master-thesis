@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
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

    public static final class Snapshot {
        private final byte[] buffer;
        public final long byteCount;
        public final int phase;
        public final State state;

        private Snapshot(byte[] buffer, long byteCount, int phase, State state) {
            this.buffer = copy(buffer);
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }

        public byte[] getBuffer() {
            return copy(buffer);
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
        public final long byteCount;
        public final int phase;
        public final Throwable readFailure;
        public final Throwable closeFailure;

        private Result(byte[] buffer, long byteCount, int phase,
                       Throwable readFailure, Throwable closeFailure) {
            this.buffer = copy(buffer);
            this.byteCount = byteCount;
            this.phase = phase;
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        public byte[] getBuffer() {
            return copy(buffer);
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

    private final Object stateLock = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private byte[] buffer = new byte[0];
    private long byteCount;
    private int phase;
    private State state = State.OPEN;
    private boolean closeRequested;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        synchronized (stateLock) {
            while (state == State.READING) {
                stateLock.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
        }

        byte[] chunk = null;
        Throwable readFailure = null;
        try {
            byte[] sourceChunk = source.read();
            if (sourceChunk != null) {
                chunk = copy(sourceChunk);
            }
        } catch (RuntimeException e) {
            readFailure = e;
        } catch (Error e) {
            readFailure = e;
        }

        boolean complete;
        synchronized (stateLock) {
            if (readFailure == null && chunk != null) {
                try {
                    appendLocked(chunk);
                } catch (RuntimeException e) {
                    readFailure = e;
                    chunk = null;
                } catch (Error e) {
                    readFailure = e;
                    chunk = null;
                }
            }

            complete = readFailure != null || chunk == null || closeRequested;
            if (complete) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            stateLock.notifyAll();
        }

        if (complete) {
            finishCompletion(readFailure);
        }
        return chunk;
    }

    public void close() {
        boolean complete = false;
        synchronized (stateLock) {
            if (state == State.CLOSED || state == State.FAILED
                    || state == State.COMPLETING) {
                return;
            }
            closeRequested = true;
            if (state == State.OPEN) {
                state = State.COMPLETING;
                stateLock.notifyAll();
                complete = true;
            }
        }

        if (complete) {
            finishCompletion(null);
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            return new Snapshot(buffer, byteCount, phase, state);
        }
    }

    private void appendLocked(byte[] chunk) {
        if (chunk.length > Integer.MAX_VALUE - buffer.length) {
            throw new OutOfMemoryError("Buffered stream is too large");
        }

        int required = buffer.length + chunk.length;
        if (required > buffer.length) {
            int capacity = buffer.length == 0 ? 16 : buffer.length;
            while (capacity < required) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = required;
                    break;
                }
                capacity *= 2;
            }

            byte[] expanded = new byte[capacity];
            for (int i = 0; i < buffer.length; i++) {
                expanded[i] = buffer[i];
            }
            for (int i = 0; i < chunk.length; i++) {
                expanded[buffer.length + i] = chunk[i];
            }
            buffer = expanded;
        }

        byteCount += chunk.length;
        for (int i = 0; i < chunk.length; i++) {
            phase = (phase + (chunk[i] & 0xff)) % 360;
        }
    }

    private void finishCompletion(Throwable initialFailure) {
        ChunkSource completionSource;
        CompletionListener completionListener;
        byte[] completedBuffer;
        long completedByteCount;
        int completedPhase;

        synchronized (stateLock) {
            completionSource = source;
            completionListener = listener;
            completedBuffer = buffer;
            completedByteCount = byteCount;
            completedPhase = phase;
        }

        Throwable failure = initialFailure;
        Throwable closeFailure = null;
        try {
            completionSource.close();
        } catch (RuntimeException e) {
            closeFailure = e;
            failure = merge(failure, e);
        } catch (Error e) {
            closeFailure = e;
            failure = merge(failure, e);
        }

        try {
            Result result = new Result(completedBuffer, completedByteCount,
                    completedPhase, initialFailure, closeFailure);
            completionListener.complete(result);
        } catch (RuntimeException e) {
            failure = merge(failure, e);
        } catch (Error e) {
            failure = merge(failure, e);
        } finally {
            synchronized (stateLock) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                stateLock.notifyAll();
            }
        }

        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    private static Throwable merge(Throwable first, Throwable later) {
        if (first == null) {
            return later;
        }
        if (first != later) {
            first.addSuppressed(later);
        }
        return first;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new AssertionError(failure);
    }

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
