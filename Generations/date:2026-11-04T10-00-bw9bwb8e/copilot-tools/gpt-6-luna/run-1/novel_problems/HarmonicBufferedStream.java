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

    public static final class Result {
        private final byte[] bytes;
        private final long byteCount;
        private final int phase;
        private final RuntimeException failure;

        private Result(byte[] bytes, long byteCount, int phase, RuntimeException failure) {
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

    private static final class CompletionWork {
        private final ChunkSource source;
        private final CompletionListener listener;
        private final byte[] bytes;
        private final long byteCount;
        private final int phase;
        private final RuntimeException failure;

        private CompletionWork(ChunkSource source, CompletionListener listener, byte[] bytes,
                               long byteCount, int phase, RuntimeException failure) {
            this.source = source;
            this.listener = listener;
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.failure = failure;
        }
    }

    private final Object lock = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private byte[] buffer = new byte[0];
    private int bufferLength;
    private long byteCount;
    private int phase;
    private State state = State.OPEN;
    private boolean closeRequested;

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
            while (state == State.READING) {
                lock.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            activeSource = source;
        }

        byte[] chunk = null;
        RuntimeException readFailure = null;
        try {
            byte[] sourceChunk = activeSource.read();
            if (sourceChunk != null) {
                chunk = copy(sourceChunk);
            }
        } catch (RuntimeException exception) {
            readFailure = exception;
        }

        CompletionWork work = null;
        synchronized (lock) {
            if (chunk != null) {
                appendLocked(chunk);
            }

            if (readFailure != null || chunk == null || closeRequested) {
                closeRequested = false;
                work = beginCompletionLocked(readFailure);
            } else {
                state = State.OPEN;
                lock.notifyAll();
            }
        }

        if (work != null) {
            completeClaimed(work);
        }
        return chunk;
    }

    public void close() {
        CompletionWork work = null;
        synchronized (lock) {
            if (state == State.OPEN) {
                work = beginCompletionLocked(null);
            } else if (state == State.READING) {
                closeRequested = true;
            }
        }

        if (work != null) {
            completeClaimed(work);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copy(buffer, bufferLength), byteCount, phase, state);
        }
    }

    private CompletionWork beginCompletionLocked(RuntimeException failure) {
        CompletionWork work = new CompletionWork(
                source,
                listener,
                copy(buffer, bufferLength),
                byteCount,
                phase,
                failure);
        state = State.COMPLETING;
        return work;
    }

    private void completeClaimed(CompletionWork work) {
        RuntimeException failure = work.failure;
        try {
            try {
                work.source.close();
            } catch (RuntimeException exception) {
                failure = combine(failure, exception);
            }

            Result result = new Result(work.bytes, work.byteCount, work.phase, failure);
            try {
                work.listener.complete(result);
            } catch (RuntimeException exception) {
                failure = combine(failure, exception);
            }
        } finally {
            synchronized (lock) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                lock.notifyAll();
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private void appendLocked(byte[] chunk) {
        if (chunk.length > Integer.MAX_VALUE - bufferLength) {
            throw new OutOfMemoryError("Buffered stream is too large");
        }

        int required = bufferLength + chunk.length;
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
            System.arraycopy(buffer, 0, expanded, 0, bufferLength);
            System.arraycopy(chunk, 0, expanded, bufferLength, chunk.length);
            buffer = expanded;
        } else {
            System.arraycopy(chunk, 0, buffer, bufferLength, chunk.length);
        }

        bufferLength = required;
        byteCount += chunk.length;
        for (int i = 0; i < chunk.length; i++) {
            phase = (phase + (chunk[i] & 255)) % 360;
        }
    }

    private static RuntimeException combine(RuntimeException earlier, RuntimeException later) {
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
        System.arraycopy(bytes, 0, result, 0, length);
        return result;
    }
}
