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
        private final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final State state;

        private Snapshot(byte[] bytes, long byteCount, int phase, State state) {
            this.bytes = copyOf(bytes);
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
        private final byte[] bytes;
        public final long byteCount;
        public final int phase;
        private final Throwable failure;

        private Result(byte[] bytes, long byteCount, int phase, Throwable failure) {
            this.bytes = copyOf(bytes);
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

        public Throwable getFailure() {
            return failure;
        }
    }

    private final Object lock = new Object();

    private ChunkSource source;
    private CompletionListener listener;

    private State state = State.OPEN;
    private boolean closeRequested;
    private Throwable failure;

    private byte[] buffer = new byte[0];
    private int used;
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

        byte[] sourceChunk = null;
        byte[] detachedChunk = null;
        Throwable readFailure = null;

        try {
            sourceChunk = activeSource.read();
            if (sourceChunk != null) {
                detachedChunk = copyOf(sourceChunk);
            }
        } catch (Throwable t) {
            readFailure = t;
        }

        boolean complete;
        synchronized (lock) {
            if (readFailure == null && detachedChunk != null) {
                try {
                    append(detachedChunk);
                } catch (Throwable t) {
                    readFailure = t;
                    detachedChunk = null;
                }
            }

            if (readFailure != null) {
                failure = merge(failure, readFailure);
            }

            boolean eof = readFailure == null && sourceChunk == null;
            complete = readFailure != null || eof || closeRequested;

            if (complete) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            lock.notifyAll();
        }

        if (complete) {
            throwIfFailure(completeClaimed());
        }

        return detachedChunk;
    }

    public void close() {
        boolean complete = false;

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
            complete = true;
        }

        if (complete) {
            throwIfFailure(completeClaimed());
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copyBuffer(), byteCount, phase, state);
        }
    }

    private void append(byte[] chunk) {
        if (chunk.length == 0) {
            return;
        }

        if (chunk.length > Integer.MAX_VALUE - used) {
            throw new OutOfMemoryError("Buffered stream is too large");
        }

        int needed = used + chunk.length;
        if (needed > buffer.length) {
            int capacity = buffer.length == 0 ? 16 : buffer.length;
            while (capacity < needed) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = needed;
                    break;
                }
                capacity *= 2;
            }

            byte[] grown = new byte[capacity];
            System.arraycopy(buffer, 0, grown, 0, used);
            buffer = grown;
        }

        System.arraycopy(chunk, 0, buffer, used, chunk.length);

        int nextPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            nextPhase = (nextPhase + (chunk[i] & 0xff)) % 360;
        }

        used = needed;
        byteCount += chunk.length;
        phase = nextPhase;
    }

    private byte[] copyBuffer() {
        byte[] result = new byte[used];
        System.arraycopy(buffer, 0, result, 0, used);
        return result;
    }

    private Throwable completeClaimed() {
        ChunkSource completingSource;
        CompletionListener completingListener;
        Throwable problem;

        synchronized (lock) {
            completingSource = source;
            completingListener = listener;
            problem = failure;
        }

        if (completingSource != null) {
            try {
                completingSource.close();
            } catch (Throwable t) {
                problem = merge(problem, t);
            }
        }

        Result result;
        synchronized (lock) {
            result = new Result(copyBuffer(), byteCount, phase, problem);
        }

        if (completingListener != null) {
            try {
                completingListener.complete(result);
            } catch (Throwable t) {
                problem = merge(problem, t);
            }
        }

        synchronized (lock) {
            failure = problem;
            state = problem == null ? State.CLOSED : State.FAILED;
            source = null;
            listener = null;
            lock.notifyAll();
        }

        return problem;
    }

    private static Throwable merge(Throwable earlier, Throwable later) {
        if (earlier == null) {
            return later;
        }
        if (later != null && earlier != later) {
            try {
                earlier.addSuppressed(later);
            } catch (Throwable ignored) {
                // Preserve the earlier failure even if suppression is unavailable.
            }
        }
        return earlier;
    }

    private static void throwIfFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new RuntimeException(failure);
    }

    private static byte[] copyOf(byte[] source) {
        byte[] result = new byte[source.length];
        System.arraycopy(source, 0, result, 0, source.length);
        return result;
    }
}
