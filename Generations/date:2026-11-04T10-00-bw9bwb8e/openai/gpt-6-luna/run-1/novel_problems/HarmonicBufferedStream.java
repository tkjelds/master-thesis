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

    public final byte[] bytes;
    public final long byteCount;
    public final int phase;
    public final RuntimeException failure;

    Result(byte[] bytes, long byteCount, int phase, RuntimeException failure) {
        this.bytes = bytes;
        this.byteCount = byteCount;
        this.phase = phase;
        this.failure = failure;
    }

    public byte[] getBytes() {
        return HarmonicBufferedStream.copyBytes(bytes);
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

@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}

    public enum State {
        OPEN,
        READING,
        COMPLETING,
        CLOSED,
        FAILED
    }

    public static final class Snapshot {
        public final byte[] buffer;
        public final long byteCount;
        public final int phase;
        public final State state;

        Snapshot(byte[] buffer, long byteCount, int phase, State state) {
            this.buffer = buffer;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }

        public byte[] getBuffer() {
            return copyBytes(buffer);
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

    private ChunkSource source;
    private CompletionListener listener;

    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[] buffer = new byte[0];
    private int bufferLength;
    private long byteCount;
    private int phase;
    private RuntimeException failure;

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
        synchronized (this) {
            while (state == State.READING) {
                wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
        }

        byte[] returnedChunk = null;
        RuntimeException readFailure = null;

        try {
            ChunkSource currentSource = source;
            byte[] rawChunk = currentSource.read();
            if (rawChunk != null) {
                returnedChunk = copyBytes(rawChunk);
            }

            synchronized (this) {
                if (returnedChunk != null) {
                    try {
                        appendChunk(returnedChunk);
                    } catch (RuntimeException e) {
                        readFailure = e;
                    }
                }

                if (readFailure != null) {
                    mergeFailure(readFailure);
                    state = State.COMPLETING;
                } else if (rawChunk == null || closeRequested) {
                    state = State.COMPLETING;
                } else {
                    state = State.OPEN;
                    notifyAll();
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                mergeFailure(e);
                state = State.COMPLETING;
            }
        }

        boolean mustComplete;
        synchronized (this) {
            mustComplete = state == State.COMPLETING;
            notifyAll();
        }

        if (mustComplete) {
            RuntimeException completionFailure = performCompletion();
            if (completionFailure != null) {
                throw completionFailure;
            }
        }

        return returnedChunk;
    }

    public void close() {
        boolean performCompletion = false;

        synchronized (this) {
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state != State.OPEN) {
                return;
            }
            state = State.COMPLETING;
            performCompletion = true;
        }

        if (performCompletion) {
            RuntimeException completionFailure = performCompletion();
            if (completionFailure != null) {
                throw completionFailure;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            byte[] detached = new byte[bufferLength];
            for (int i = 0; i < bufferLength; i++) {
                detached[i] = buffer[i];
            }
            return new Snapshot(detached, byteCount, phase, state);
        }
    }

    private void appendChunk(byte[] chunk) {
        if (chunk.length > Integer.MAX_VALUE - bufferLength) {
            throw new IllegalStateException("Buffered data exceeds maximum array size");
        }

        int requiredLength = bufferLength + chunk.length;
        if (requiredLength > buffer.length) {
            int newCapacity = buffer.length == 0 ? 16 : buffer.length;
            while (newCapacity < requiredLength) {
                if (newCapacity > Integer.MAX_VALUE / 2) {
                    newCapacity = requiredLength;
                    break;
                }
                newCapacity *= 2;
            }

            byte[] expanded = new byte[newCapacity];
            for (int i = 0; i < bufferLength; i++) {
                expanded[i] = buffer[i];
            }
            buffer = expanded;
        }

        for (int i = 0; i < chunk.length; i++) {
            byte value = chunk[i];
            buffer[bufferLength + i] = value;
            phase = (phase + (value & 0xff)) % 360;
        }

        bufferLength = requiredLength;
        byteCount += chunk.length;
    }

    private RuntimeException performCompletion() {
        ChunkSource completionSource;
        CompletionListener completionListener;

        synchronized (this) {
            completionSource = source;
            completionListener = listener;
        }

        try {
            completionSource.close();
        } catch (RuntimeException e) {
            synchronized (this) {
                mergeFailure(e);
            }
        }

        Result result;
        synchronized (this) {
            byte[] detached = new byte[bufferLength];
            for (int i = 0; i < bufferLength; i++) {
                detached[i] = buffer[i];
            }
            result = new Result(detached, byteCount, phase, failure);
        }

        try {
            completionListener.complete(result);
        } catch (RuntimeException e) {
            synchronized (this) {
                mergeFailure(e);
            }
        } finally {
            synchronized (this) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                notifyAll();
            }
        }

        synchronized (this) {
            return failure;
        }
    }

    private void mergeFailure(RuntimeException later) {
        if (failure == null) {
            failure = later;
        } else if (failure != later) {
            failure.addSuppressed(later);
        }
    }

    static byte[] copyBytes(byte[] source) {
        if (source == null) {
            return null;
        }
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
