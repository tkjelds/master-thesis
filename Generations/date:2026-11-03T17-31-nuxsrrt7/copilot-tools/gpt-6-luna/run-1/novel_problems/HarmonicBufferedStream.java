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
        public final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final Throwable failure;

        private Result(byte[] bytes, long byteCount, int phase, Throwable failure) {
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

        private Snapshot(byte[] bytes, long byteCount, int phase, State state) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }
    }

    private final Object stateMonitor = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[][] chunks = new byte[8][];
    private int chunkCount;
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

        byte[] chunk = null;
        Throwable readFailure = null;
        try {
            byte[] supplied = activeSource.read();
            if (supplied != null) {
                chunk = copy(supplied);
            }
        } catch (RuntimeException | Error failure) {
            readFailure = failure;
        }

        byte[] returnedChunk = null;
        boolean mustComplete = false;
        synchronized (stateMonitor) {
            if (chunk != null) {
                appendChunkLocked(chunk);
                returnedChunk = copy(chunk);
            }

            if (readFailure != null || chunk == null || closeRequested) {
                state = State.COMPLETING;
                mustComplete = true;
            } else {
                state = State.OPEN;
                stateMonitor.notifyAll();
            }
        }

        if (mustComplete) {
            Throwable completionFailure = performCompletion(readFailure);
            rethrow(completionFailure);
        }
        return returnedChunk;
    }

    public void close() {
        boolean mustComplete = false;
        synchronized (stateMonitor) {
            if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                return;
            }
            closeRequested = true;
            if (state == State.OPEN) {
                state = State.COMPLETING;
                mustComplete = true;
            }
        }

        if (mustComplete) {
            rethrow(performCompletion(null));
        }
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            return new Snapshot(copyBufferLocked(), byteCount, phase, state);
        }
    }

    private void appendChunkLocked(byte[] chunk) {
        if (chunkCount == chunks.length) {
            int newLength = chunks.length * 2;
            if (newLength <= chunks.length) {
                throw new OutOfMemoryError("Too many chunks");
            }
            byte[][] expanded = new byte[newLength][];
            System.arraycopy(chunks, 0, expanded, 0, chunks.length);
            chunks = expanded;
        }
        chunks[chunkCount++] = chunk;
        byteCount += chunk.length;
        for (int i = 0; i < chunk.length; i++) {
            phase = (phase + (chunk[i] & 0xff)) % 360;
        }
    }

    private byte[] copyBufferLocked() {
        if (byteCount > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Buffered data exceeds the maximum array size");
        }
        byte[] result = new byte[(int) byteCount];
        int offset = 0;
        for (int i = 0; i < chunkCount; i++) {
            byte[] chunk = chunks[i];
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private Throwable performCompletion(Throwable failure) {
        ChunkSource completingSource;
        CompletionListener completingListener;
        synchronized (stateMonitor) {
            completingSource = source;
            completingListener = listener;
        }

        try {
            completingSource.close();
        } catch (RuntimeException | Error closeFailure) {
            failure = combine(failure, closeFailure);
        }

        Result result;
        synchronized (stateMonitor) {
            result = new Result(copyBufferLocked(), byteCount, phase, failure);
        }

        try {
            completingListener.complete(result);
        } catch (RuntimeException | Error listenerFailure) {
            failure = combine(failure, listenerFailure);
        } finally {
            synchronized (stateMonitor) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                stateMonitor.notifyAll();
            }
        }
        return failure;
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

    private static byte[] copy(byte[] bytes) {
        byte[] result = new byte[bytes.length];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        return result;
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
