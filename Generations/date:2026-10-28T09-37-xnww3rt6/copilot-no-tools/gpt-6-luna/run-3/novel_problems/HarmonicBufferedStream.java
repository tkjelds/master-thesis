@HarmonicBufferedStream.ThreadSafe
public final class HarmonicBufferedStream {
    @interface ThreadSafe {}

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
            return copyBytes(bytes);
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

    private byte[] buffer = new byte[0];
    private int bufferLength;
    private long byteCount;
    private int phase;
    private State state = State.OPEN;
    private boolean closeRequested;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null || listener == null) {
            throw new IllegalArgumentException("source and listener must not be null");
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        ChunkSource activeSource;

        synchronized (monitor) {
            while (true) {
                if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                    return null;
                }
                if (state == State.OPEN) {
                    state = State.READING;
                    activeSource = source;
                    break;
                }
                monitor.wait();
            }
        }

        byte[] chunk = null;
        RuntimeException readFailure = null;

        try {
            byte[] sourceChunk = activeSource.read();
            if (sourceChunk != null) {
                chunk = copyBytes(sourceChunk);
            }
        } catch (RuntimeException exception) {
            readFailure = exception;
        }

        boolean complete;
        synchronized (monitor) {
            if (chunk != null) {
                append(chunk);
            }

            complete = readFailure != null || chunk == null || closeRequested;
            if (complete) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            monitor.notifyAll();
        }

        if (complete) {
            RuntimeException failure = completeStream(readFailure);
            if (failure != null) {
                throw failure;
            }
        }

        return chunk;
    }

    public void close() {
        boolean complete = false;

        synchronized (monitor) {
            if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                return;
            }
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state == State.OPEN) {
                closeRequested = true;
                state = State.COMPLETING;
                complete = true;
            }
        }

        if (complete) {
            RuntimeException failure = completeStream(null);
            if (failure != null) {
                throw failure;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(copyBytes(buffer, bufferLength), byteCount, phase, state);
        }
    }

    private RuntimeException completeStream(RuntimeException readFailure) {
        ChunkSource sourceToClose;
        CompletionListener listenerToCall;
        byte[] completedBytes;
        long completedByteCount;
        int completedPhase;

        synchronized (monitor) {
            sourceToClose = source;
            listenerToCall = listener;
            completedBytes = copyBytes(buffer, bufferLength);
            completedByteCount = byteCount;
            completedPhase = phase;
        }

        RuntimeException failure = readFailure;
        RuntimeException closeFailure = null;

        try {
            try {
                sourceToClose.close();
            } catch (RuntimeException exception) {
                closeFailure = exception;
                failure = combine(failure, exception);
            }

            Result result = new Result(
                completedBytes,
                completedByteCount,
                completedPhase,
                readFailure,
                closeFailure,
                failure
            );

            try {
                listenerToCall.complete(result);
            } catch (RuntimeException exception) {
                failure = combine(failure, exception);
            }
        } finally {
            synchronized (monitor) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                monitor.notifyAll();
            }
        }

        return failure;
    }

    private void append(byte[] chunk) {
        int needed = bufferLength + chunk.length;
        if (needed < bufferLength) {
            throw new OutOfMemoryError("Buffered stream is too large");
        }

        if (needed > buffer.length) {
            int newCapacity = buffer.length == 0 ? 16 : buffer.length;
            while (newCapacity < needed) {
                if (newCapacity > Integer.MAX_VALUE / 2) {
                    newCapacity = needed;
                    break;
                }
                newCapacity *= 2;
            }

            byte[] newBuffer = new byte[newCapacity];
            for (int i = 0; i < bufferLength; i++) {
                newBuffer[i] = buffer[i];
            }
            buffer = newBuffer;
        }

        for (int i = 0; i < chunk.length; i++) {
            byte value = chunk[i];
            buffer[bufferLength + i] = value;
            phase = (phase + (value & 0xFF)) % 360;
        }

        bufferLength = needed;
        byteCount += chunk.length;
    }

    private static RuntimeException combine(RuntimeException earlier, RuntimeException later) {
        if (earlier == null) {
            return later;
        }
        if (later != null && earlier != later) {
            earlier.addSuppressed(later);
        }
        return earlier;
    }

    private static byte[] copyBytes(byte[] source) {
        return copyBytes(source, source.length);
    }

    private static byte[] copyBytes(byte[] source, int length) {
        byte[] copy = new byte[length];
        for (int i = 0; i < length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}

interface ChunkSource {
    byte[] read();

    void close();
}

interface CompletionListener {
    void complete(Result result);
}

@Result.ThreadSafe
final class Result {
    @interface ThreadSafe {}

    private final byte[] bytes;
    private final long byteCount;
    private final int phase;
    private final RuntimeException readFailure;
    private final RuntimeException closeFailure;
    private final RuntimeException failure;

    Result(
        byte[] bytes,
        long byteCount,
        int phase,
        RuntimeException readFailure,
        RuntimeException closeFailure,
        RuntimeException failure
    ) {
        this.bytes = bytes;
        this.byteCount = byteCount;
        this.phase = phase;
        this.readFailure = readFailure;
        this.closeFailure = closeFailure;
        this.failure = failure;
    }

    public byte[] getBytes() {
        byte[] copy = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            copy[i] = bytes[i];
        }
        return copy;
    }

    public long getByteCount() {
        return byteCount;
    }

    public int getPhase() {
        return phase;
    }

    public RuntimeException getReadFailure() {
        return readFailure;
    }

    public RuntimeException getCloseFailure() {
        return closeFailure;
    }

    public RuntimeException getFailure() {
        return failure;
    }
}
