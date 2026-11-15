@HarmonicBufferedStream.ThreadSafe
I’ll add the self-contained implementation as `HarmonicBufferedStream.java` and verify it compiles without changing the existing unrelated worktree changes.
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

    public static final class Result {
        public final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final RuntimeException failure;

        private Result(byte[] bytes, long byteCount, int phase,
                       RuntimeException failure) {
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

        public RuntimeException getFailure() {
            return failure;
        }
    }

    public static final class Snapshot {
        public final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final State state;

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

    private final Object stateMonitor = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[] buffer = new byte[0];
    private int bufferedLength;
    private long byteCount;
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
        synchronized (stateMonitor) {
            while (state == State.READING) {
                stateMonitor.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            activeSource = source;
        }

        byte[] detachedChunk = null;
        RuntimeException readFailure = null;
        try {
            byte[] sourceChunk = activeSource.read();
            if (sourceChunk != null) {
                detachedChunk = copy(sourceChunk);
            }
        } catch (RuntimeException failure) {
            readFailure = failure;
        }

        boolean complete = false;
        synchronized (stateMonitor) {
            if (readFailure != null) {
                state = State.COMPLETING;
                complete = true;
            } else if (detachedChunk == null) {
                state = State.COMPLETING;
                complete = true;
            } else {
                try {
                    append(detachedChunk);
                } catch (RuntimeException appendFailure) {
                    readFailure = appendFailure;
                }
                if (readFailure != null || closeRequested) {
                    state = State.COMPLETING;
                    complete = true;
                } else {
                    state = State.OPEN;
                }
            }
            stateMonitor.notifyAll();
        }

        if (complete) {
            finishCompletion(readFailure);
        }
        return detachedChunk;
    }

    public void close() {
        boolean complete = false;
        synchronized (stateMonitor) {
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state != State.OPEN) {
                return;
            }
            state = State.COMPLETING;
            complete = true;
            stateMonitor.notifyAll();
        }
        if (complete) {
            finishCompletion(null);
        }
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            return new Snapshot(copy(buffer, bufferedLength), byteCount,
                    phase, state);
        }
    }

    private void append(byte[] chunk) {
        if (chunk.length > Integer.MAX_VALUE - bufferedLength) {
            throw new IllegalStateException("Buffered stream is too large");
        }
        int requiredLength = bufferedLength + chunk.length;
        if (requiredLength > buffer.length) {
            int newCapacity = buffer.length == 0 ? 16 : buffer.length;
            while (newCapacity < requiredLength) {
                if (newCapacity > Integer.MAX_VALUE / 2) {
                    newCapacity = Integer.MAX_VALUE;
                    break;
                }
                newCapacity *= 2;
            }
            byte[] expanded = new byte[newCapacity];
            for (int i = 0; i < bufferedLength; i++) {
                expanded[i] = buffer[i];
            }
            buffer = expanded;
        }

        int chunkPhase = 0;
        for (int i = 0; i < chunk.length; i++) {
            byte value = chunk[i];
            buffer[bufferedLength + i] = value;
            chunkPhase = (chunkPhase + (value & 0xff)) % 360;
        }
        bufferedLength = requiredLength;
        byteCount += chunk.length;
        phase = (phase + chunkPhase) % 360;
    }

    private void finishCompletion(RuntimeException failure) {
        ChunkSource completionSource;
        CompletionListener completionListener;
        Result result;
        synchronized (stateMonitor) {
            completionSource = source;
            completionListener = listener;
            result = new Result(copy(buffer, bufferedLength), byteCount,
                    phase, null);
        }

        try {
            try {
                completionSource.close();
            } catch (RuntimeException closeFailure) {
                failure = mergeFailures(failure, closeFailure);
            }
            result = new Result(result.bytes, result.byteCount, result.phase,
                    failure);
            try {
                completionListener.complete(result);
            } catch (RuntimeException listenerFailure) {
                failure = mergeFailures(failure, listenerFailure);
            }
        } finally {
            synchronized (stateMonitor) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                stateMonitor.notifyAll();
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException mergeFailures(RuntimeException earlier,
                                                   RuntimeException later) {
        if (earlier == null) {
            return later;
        }
        if (earlier != later) {
            earlier.addSuppressed(later);
        }
        return earlier;
    }

    private static byte[] copy(byte[] bytes) {
        return copy(bytes, bytes.length);
    }

    private static byte[] copy(byte[] bytes, int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = bytes[i];
        }
        return result;
    }
}
