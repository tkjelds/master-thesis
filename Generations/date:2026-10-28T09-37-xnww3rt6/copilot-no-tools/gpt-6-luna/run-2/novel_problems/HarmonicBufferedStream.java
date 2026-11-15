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
    public final RuntimeException readFailure;
    public final RuntimeException closeFailure;

    Result(byte[] bytes, long byteCount, int phase,
           RuntimeException failure, RuntimeException readFailure,
           RuntimeException closeFailure) {
        this.bytes = bytes;
        this.byteCount = byteCount;
        this.phase = phase;
        this.failure = failure;
        this.readFailure = readFailure;
        this.closeFailure = closeFailure;
    }
}

@Snapshot.ThreadSafe
final class Snapshot {
    @interface ThreadSafe {}

    public final byte[] bytes;
    public final long byteCount;
    public final int phase;
    public final HarmonicBufferedStream.State state;

    Snapshot(byte[] bytes, long byteCount, int phase,
             HarmonicBufferedStream.State state) {
        this.bytes = bytes;
        this.byteCount = byteCount;
        this.phase = phase;
        this.state = state;
    }
}

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

    private final Object lock = new Object();

    private ChunkSource source;
    private CompletionListener listener;

    private State state = State.OPEN;
    private boolean closeRequested;
    private RuntimeException terminalFailure;

    private byte[][] chunks = new byte[8][];
    private int chunkCount;
    private long byteCount;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source,
                                  CompletionListener listener) {
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        synchronized (lock) {
            while (state == State.READING) {
                lock.wait();
            }

            if (state != State.OPEN) {
                return null;
            }

            state = State.READING;
        }

        byte[] sourceChunk = null;
        RuntimeException readFailure = null;

        try {
            sourceChunk = source.read();
        } catch (RuntimeException exception) {
            readFailure = exception;
        }

        byte[] retainedChunk = sourceChunk == null
                ? null
                : copyBytes(sourceChunk);
        byte[] returnedChunk = retainedChunk == null
                ? null
                : copyBytes(retainedChunk);

        boolean complete;
        synchronized (lock) {
            if (readFailure == null && retainedChunk != null) {
                appendChunkLocked(retainedChunk);
            }

            complete = readFailure != null
                    || sourceChunk == null
                    || closeRequested;

            if (complete) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            lock.notifyAll();
        }

        if (complete) {
            completeClaim(readFailure);
        }

        return returnedChunk;
    }

    public void close() {
        boolean complete = false;

        synchronized (lock) {
            if (state == State.OPEN) {
                state = State.COMPLETING;
                complete = true;
            } else if (state == State.READING) {
                closeRequested = true;
                return;
            } else {
                return;
            }
        }

        if (complete) {
            completeClaim(null);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    flattenLocked(),
                    byteCount,
                    phase,
                    state);
        }
    }

    private void appendChunkLocked(byte[] chunk) {
        if (byteCount > Long.MAX_VALUE - chunk.length) {
            throw new IllegalStateException("Byte count overflow");
        }

        int chunkPhase = 0;
        for (int i = 0; i < chunk.length; i++) {
            chunkPhase = (chunkPhase + (chunk[i] & 0xff)) % 360;
        }

        if (chunkCount == chunks.length) {
            int newCapacity = chunks.length < 16
                    ? chunks.length * 2
                    : chunks.length + chunks.length / 2;
            if (newCapacity <= chunks.length) {
                throw new OutOfMemoryError("Too many chunks");
            }

            byte[][] expanded = new byte[newCapacity][];
            System.arraycopy(chunks, 0, expanded, 0, chunkCount);
            chunks = expanded;
        }

        chunks[chunkCount] = chunk;
        chunkCount++;
        byteCount += chunk.length;
        phase = (phase + chunkPhase) % 360;
    }

    private void completeClaim(RuntimeException readFailure) {
        ChunkSource sourceToClose;
        CompletionListener listenerToCall;

        synchronized (lock) {
            sourceToClose = source;
            listenerToCall = listener;
        }

        RuntimeException failure = readFailure;
        RuntimeException closeFailure = null;

        try {
            try {
                sourceToClose.close();
            } catch (RuntimeException exception) {
                closeFailure = exception;
                failure = combineFailures(failure, exception);
            }

            Result result;
            synchronized (lock) {
                result = new Result(
                        flattenLocked(),
                        byteCount,
                        phase,
                        failure,
                        readFailure,
                        closeFailure);
            }

            try {
                listenerToCall.complete(result);
            } catch (RuntimeException exception) {
                failure = combineFailures(failure, exception);
            }
        } finally {
            synchronized (lock) {
                source = null;
                listener = null;
                terminalFailure = failure;
                state = failure == null ? State.CLOSED : State.FAILED;
                lock.notifyAll();
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private byte[] flattenLocked() {
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalStateException("Buffered data exceeds array capacity");
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

    private static byte[] copyBytes(byte[] bytes) {
        byte[] copy = new byte[bytes.length];
        System.arraycopy(bytes, 0, copy, 0, bytes.length);
        return copy;
    }

    private static RuntimeException combineFailures(
            RuntimeException earlier,
            RuntimeException later) {
        if (earlier == null) {
            return later;
        }
        if (earlier != later) {
            earlier.addSuppressed(later);
        }
        return earlier;
    }
}
