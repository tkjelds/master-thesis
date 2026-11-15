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
            return copyBytes(bytes);
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
        synchronized (lock) {
            while (state == State.READING) {
                lock.wait();
            }
            if (state != State.OPEN || closeRequested) {
                return null;
            }
            state = State.READING;
        }

        byte[] sourceChunk = null;
        byte[] ownedChunk = null;
        RuntimeException readFailure = null;

        try {
            sourceChunk = source.read();
            if (sourceChunk != null) {
                ownedChunk = copyBytes(sourceChunk);
            }
        } catch (RuntimeException exception) {
            readFailure = exception;
        }

        boolean completeNow;
        synchronized (lock) {
            if (ownedChunk != null) {
                append(ownedChunk);
            }

            completeNow = readFailure != null || ownedChunk == null || closeRequested;
            if (completeNow) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            lock.notifyAll();
        }

        if (completeNow) {
            RuntimeException completionFailure = finishCompletion(readFailure);
            if (completionFailure != null) {
                throw completionFailure;
            }
        }

        return ownedChunk;
    }

    public void close() {
        boolean completeNow = false;

        synchronized (lock) {
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state != State.OPEN) {
                return;
            }

            closeRequested = true;
            state = State.COMPLETING;
            lock.notifyAll();
            completeNow = true;
        }

        if (completeNow) {
            RuntimeException failure = finishCompletion(null);
            if (failure != null) {
                throw failure;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copyBuffer(), byteCount, phase, state);
        }
    }

    private RuntimeException finishCompletion(RuntimeException failure) {
        ChunkSource sourceToClose;
        CompletionListener listenerToCall;
        byte[] completedBytes;
        long completedCount;
        int completedPhase;

        synchronized (lock) {
            sourceToClose = source;
            listenerToCall = listener;
            completedBytes = copyBuffer();
            completedCount = byteCount;
            completedPhase = phase;
        }

        try {
            sourceToClose.close();
        } catch (RuntimeException exception) {
            failure = combineFailures(failure, exception);
        }

        Result result = new Result(completedBytes, completedCount, completedPhase, failure);
        try {
            listenerToCall.complete(result);
        } catch (RuntimeException exception) {
            failure = combineFailures(failure, exception);
        }

        synchronized (lock) {
            source = null;
            listener = null;
            state = failure == null ? State.CLOSED : State.FAILED;
            lock.notifyAll();
        }

        return failure;
    }

    private void append(byte[] chunk) {
        int newLength = bufferLength + chunk.length;
        if (newLength < bufferLength) {
            throw new OutOfMemoryError("Buffered stream is too large");
        }

        if (newLength > buffer.length) {
            int newCapacity = buffer.length == 0 ? 16 : buffer.length;
            while (newCapacity < newLength) {
                int doubled = newCapacity << 1;
                if (doubled <= newCapacity) {
                    newCapacity = newLength;
                    break;
                }
                newCapacity = doubled;
            }
            byte[] expanded = new byte[newCapacity];
            System.arraycopy(buffer, 0, expanded, 0, bufferLength);
            buffer = expanded;
        }

        System.arraycopy(chunk, 0, buffer, bufferLength, chunk.length);
        bufferLength = newLength;
        byteCount += chunk.length;

        for (int i = 0; i < chunk.length; i++) {
            phase = (phase + (chunk[i] & 0xff)) % 360;
        }
    }

    private byte[] copyBuffer() {
        byte[] copy = new byte[bufferLength];
        System.arraycopy(buffer, 0, copy, 0, bufferLength);
        return copy;
    }

    private static byte[] copyBytes(byte[] bytes) {
        byte[] copy = new byte[bytes.length];
        System.arraycopy(bytes, 0, copy, 0, bytes.length);
        return copy;
    }

    private static RuntimeException combineFailures(
            RuntimeException primary, RuntimeException later) {
        if (primary == null) {
            return later;
        }
        if (primary != later) {
            primary.addSuppressed(later);
        }
        return primary;
    }
}
