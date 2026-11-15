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
        OPEN,
        READING,
        COMPLETING,
        CLOSED,
        FAILED
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

    public static final class Result {
        private final byte[] bytes;
        private final long byteCount;
        private final int phase;
        private final RuntimeException failure;

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

    private final Object lock = new Object();
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
            throw new IllegalArgumentException("source must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        ChunkSource sourceForRead;
        synchronized (lock) {
            while (state == State.READING) {
                lock.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            sourceForRead = source;
        }

        byte[] chunk = null;
        RuntimeException readFailure = null;
        try {
            byte[] sourceChunk = sourceForRead.read();
            if (sourceChunk != null) {
                chunk = copy(sourceChunk);
            }
        } catch (RuntimeException failure) {
            readFailure = failure;
        }

        boolean complete;
        synchronized (lock) {
            if (readFailure == null && chunk != null) {
                append(chunk);
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
            finishCompletion(readFailure);
        }
        return chunk;
    }

    public void close() {
        boolean complete = false;
        synchronized (lock) {
            if (state == State.OPEN) {
                state = State.COMPLETING;
                complete = true;
                lock.notifyAll();
            } else if (state == State.READING) {
                closeRequested = true;
            }
        }
        if (complete) {
            finishCompletion(null);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copy(buffer, bufferedLength), byteCount, phase,
                                state);
        }
    }

    private void append(byte[] chunk) {
        if (chunk.length > Integer.MAX_VALUE - bufferedLength) {
            throw new OutOfMemoryError("stream buffer is too large");
        }
        int newLength = bufferedLength + chunk.length;
        if (newLength > buffer.length) {
            int capacity = buffer.length == 0 ? 16 : buffer.length;
            while (capacity < newLength) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = newLength;
                } else {
                    capacity *= 2;
                }
            }
            byte[] expanded = new byte[capacity];
            System.arraycopy(buffer, 0, expanded, 0, bufferedLength);
            buffer = expanded;
        }
        System.arraycopy(chunk, 0, buffer, bufferedLength, chunk.length);
        bufferedLength = newLength;
        byteCount += chunk.length;
        for (int i = 0; i < chunk.length; i++) {
            phase = (phase + (chunk[i] & 0xff)) % 360;
        }
    }

    private void finishCompletion(RuntimeException initialFailure) {
        ChunkSource sourceForClose;
        CompletionListener listenerForCompletion;
        synchronized (lock) {
            sourceForClose = source;
            listenerForCompletion = listener;
        }

        RuntimeException failure = initialFailure;
        try {
            try {
                sourceForClose.close();
            } catch (RuntimeException closeFailure) {
                failure = merge(failure, closeFailure);
            }

            Result result;
            synchronized (lock) {
                result = new Result(copy(buffer, bufferedLength), byteCount,
                                    phase, failure);
            }
            try {
                listenerForCompletion.complete(result);
            } catch (RuntimeException listenerFailure) {
                failure = merge(failure, listenerFailure);
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

    private static RuntimeException merge(RuntimeException earlier,
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
        System.arraycopy(bytes, 0, result, 0, length);
        return result;
    }
}
