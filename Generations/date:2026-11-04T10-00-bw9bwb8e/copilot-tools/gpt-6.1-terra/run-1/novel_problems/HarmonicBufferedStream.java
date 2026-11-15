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
        OPEN, READING, COMPLETING, CLOSED, FAILED
    }

    public static final class Result {
        private final byte[] bytes;
        private final long byteCount;
        private final int phase;
        private final Throwable failure;

        private Result(byte[] bytes, long byteCount, int phase, Throwable failure) {
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

        public Throwable getFailure() {
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

    private final Object monitor = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[] buffer = new byte[0];
    private int size;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null || listener == null) {
            throw new NullPointerException("source and listener must not be null");
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        ChunkSource currentSource;
        synchronized (monitor) {
            while (state == State.READING) {
                monitor.wait();
            }
            if (state != State.OPEN || closeRequested) {
                return null;
            }
            state = State.READING;
            currentSource = source;
        }

        byte[] chunk = null;
        Throwable failure = null;
        try {
            byte[] read = currentSource.read();
            if (read != null) {
                chunk = copy(read);
            }
        } catch (Throwable t) {
            failure = t;
        }

        boolean complete;
        synchronized (monitor) {
            if (failure == null && chunk != null) {
                try {
                    append(chunk);
                } catch (Throwable t) {
                    failure = t;
                }
            }
            complete = failure != null || chunk == null || closeRequested;
            state = complete ? State.COMPLETING : State.OPEN;
            monitor.notifyAll();
        }

        if (complete) {
            finishCompletion(failure);
        }
        return chunk;
    }

    public void close() {
        synchronized (monitor) {
            if (state == State.READING) {
                closeRequested = true;
                monitor.notifyAll();
                return;
            }
            if (state != State.OPEN) {
                return;
            }
            closeRequested = true;
            state = State.COMPLETING;
            monitor.notifyAll();
        }
        finishCompletion(null);
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(bufferCopy(), size, phase, state);
        }
    }

    private void append(byte[] chunk) {
        long required = (long) size + chunk.length;
        if (required > 2147483647L) {
            throw new IllegalStateException("buffer exceeds maximum array size");
        }
        int newSize = (int) required;
        int newPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            newPhase = (newPhase + (chunk[i] & 255)) % 360;
        }
        if (newSize > buffer.length) {
            long capacity = buffer.length == 0 ? 1 : buffer.length;
            while (capacity < required) {
                capacity = capacity * 2;
                if (capacity > 2147483647L) {
                    capacity = 2147483647L;
                }
            }
            byte[] expanded = new byte[(int) capacity];
            for (int i = 0; i < size; i++) {
                expanded[i] = buffer[i];
            }
            buffer = expanded;
        }
        for (int i = 0; i < chunk.length; i++) {
            buffer[size + i] = chunk[i];
        }
        size = newSize;
        phase = newPhase;
    }

    private void finishCompletion(Throwable failure) {
        ChunkSource currentSource;
        CompletionListener currentListener;
        synchronized (monitor) {
            currentSource = source;
            currentListener = listener;
        }

        try {
            try {
                currentSource.close();
            } catch (Throwable t) {
                failure = combine(failure, t);
            }

            Result result;
            synchronized (monitor) {
                result = new Result(bufferCopy(), size, phase, failure);
            }
            try {
                currentListener.complete(result);
            } catch (Throwable t) {
                failure = combine(failure, t);
            }
        } catch (Throwable t) {
            failure = combine(failure, t);
        } finally {
            synchronized (monitor) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                monitor.notifyAll();
            }
        }
        rethrow(failure);
    }

    private byte[] bufferCopy() {
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) {
            result[i] = buffer[i];
        }
        return result;
    }

    private static byte[] copy(byte[] bytes) {
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = bytes[i];
        }
        return result;
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

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure != null) {
            throw new RuntimeException(failure);
        }
    }
}
