interface ChunkSource {
    byte[] read();

    void close();
}

interface CompletionListener {
    void complete(HarmonicBufferedStream.Result result);
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
            return copyOf(bytes);
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
            return copyOf(bytes);
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
    private int bufferLength;
    private int phase;
    private RuntimeException terminalFailure;

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

    /**
     * Reads and returns the next detached chunk. If another caller is reading,
     * this call waits interruptibly. Returns null once completion has begun.
     */
    public byte[] readChunk() throws InterruptedException {
        ChunkSource currentSource;

        synchronized (lock) {
            while (state == State.READING) {
                lock.wait();
            }

            if (state != State.OPEN) {
                return null;
            }

            state = State.READING;
            currentSource = source;
        }

        byte[] sourceChunk = null;
        byte[] copiedChunk = null;
        RuntimeException readFailure = null;

        try {
            sourceChunk = currentSource.read();
            if (sourceChunk != null) {
                copiedChunk = copyOf(sourceChunk);
            }
        } catch (RuntimeException failure) {
            readFailure = failure;
        }

        boolean shouldComplete;
        synchronized (lock) {
            if (readFailure == null && copiedChunk != null) {
                try {
                    appendLocked(copiedChunk);
                } catch (RuntimeException failure) {
                    readFailure = failure;
                }
            }

            if (readFailure != null) {
                terminalFailure = readFailure;
            }

            shouldComplete = readFailure != null
                    || copiedChunk == null
                    || closeRequested;

            if (shouldComplete) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            lock.notifyAll();
        }

        if (shouldComplete) {
            complete();
        }

        return copiedChunk == null ? null : copyOf(copiedChunk);
    }

    /**
     * Requests completion. If a read is in progress, this method returns
     * immediately and that reader completes the stream after its read finishes.
     */
    public void close() {
        boolean completeHere = false;

        synchronized (lock) {
            if (state == State.OPEN) {
                closeRequested = true;
                state = State.COMPLETING;
                completeHere = true;
                lock.notifyAll();
            } else if (state == State.READING) {
                closeRequested = true;
                lock.notifyAll();
            } else {
                return;
            }
        }

        if (completeHere) {
            complete();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copyBufferLocked(), bufferLength, phase, state);
        }
    }

    private void appendLocked(byte[] chunk) {
        long requiredLong = (long) bufferLength + (long) chunk.length;
        if (requiredLong > 2147483647L) {
            throw new IllegalStateException("buffer is too large");
        }

        int required = (int) requiredLong;
        ensureCapacityLocked(required);

        int updatedPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            updatedPhase = (updatedPhase + (chunk[i] & 255)) % 360;
        }

        for (int i = 0; i < chunk.length; i++) {
            buffer[bufferLength + i] = chunk[i];
        }

        bufferLength = required;
        phase = updatedPhase;
    }

    private void ensureCapacityLocked(int required) {
        if (required <= buffer.length) {
            return;
        }

        long capacity = buffer.length == 0 ? 32L : buffer.length;
        while (capacity < required) {
            capacity = capacity + capacity / 2L + 1L;
            if (capacity > 2147483647L) {
                capacity = 2147483647L;
                break;
            }
        }

        byte[] expanded = new byte[(int) capacity];
        for (int i = 0; i < bufferLength; i++) {
            expanded[i] = buffer[i];
        }
        buffer = expanded;
    }

    private byte[] copyBufferLocked() {
        byte[] copy = new byte[bufferLength];
        for (int i = 0; i < bufferLength; i++) {
            copy[i] = buffer[i];
        }
        return copy;
    }

    private void complete() {
        ChunkSource completionSource;
        CompletionListener completionListener;
        RuntimeException failure;

        synchronized (lock) {
            completionSource = source;
            completionListener = listener;
            failure = terminalFailure;
        }

        try {
            try {
                completionSource.close();
            } catch (RuntimeException closeFailure) {
                failure = mergeFailures(failure, closeFailure);
            }

            byte[] completedBytes;
            int completedPhase;
            int completedLength;
            synchronized (lock) {
                completedBytes = copyBufferLocked();
                completedLength = bufferLength;
                completedPhase = phase;
            }

            Result result = new Result(
                    completedBytes, completedLength, completedPhase, failure);

            try {
                completionListener.complete(result);
            } catch (RuntimeException listenerFailure) {
                failure = mergeFailures(failure, listenerFailure);
            }
        } finally {
            synchronized (lock) {
                terminalFailure = failure;
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

    private static RuntimeException mergeFailures(RuntimeException earlier,
                                                   RuntimeException later) {
        if (earlier == null) {
            return later;
        }
        if (later != null && earlier != later) {
            earlier.addSuppressed(later);
        }
        return earlier;
    }

    private static byte[] copyOf(byte[] input) {
        byte[] copy = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            copy[i] = input[i];
        }
        return copy;
    }
}
