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
            return copyBytes(bytes, bytes.length);
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
            return copyBytes(bytes, bytes.length);
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
    private long byteCount;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null || listener == null) {
            throw new NullPointerException("source and listener must not be null");
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        ChunkSource activeSource;
        synchronized (monitor) {
            while (state == State.READING) {
                monitor.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            activeSource = source;
        }

        byte[] chunk = null;
        Throwable failure = null;
        try {
            byte[] incoming = activeSource.read();
            if (incoming != null) {
                chunk = copyBytes(incoming, incoming.length);
            }
        } catch (Throwable error) {
            failure = error;
        }

        boolean completing;
        synchronized (monitor) {
            if (failure == null && chunk != null) {
                try {
                    append(chunk);
                } catch (Throwable error) {
                    failure = error;
                }
            }

            completing = failure != null || chunk == null || closeRequested;
            state = completing ? State.COMPLETING : State.OPEN;
            monitor.notifyAll();
        }

        if (completing) {
            complete(failure);
        }
        return chunk;
    }

    public void close() {
        synchronized (monitor) {
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state != State.OPEN) {
                return;
            }
            closeRequested = true;
            state = State.COMPLETING;
            monitor.notifyAll();
        }

        complete(null);
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(copyBytes(buffer, size), byteCount, phase, state);
        }
    }

    private void append(byte[] chunk) {
        if (chunk.length > 2147483647 - size) {
            throw new IllegalStateException("buffer exceeds maximum array size");
        }

        int newSize = size + chunk.length;
        int newPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            newPhase = (newPhase + (chunk[i] & 255)) % 360;
        }

        if (newSize > buffer.length) {
            long capacity = buffer.length == 0 ? 32L : (long) buffer.length * 2;
            if (capacity < newSize) {
                capacity = newSize;
            }
            if (capacity > 2147483647L) {
                capacity = 2147483647L;
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
        byteCount += chunk.length;
        phase = newPhase;
    }

    private void complete(Throwable failure) {
        ChunkSource completingSource;
        CompletionListener completingListener;
        synchronized (monitor) {
            completingSource = source;
            completingListener = listener;
        }

        try {
            try {
                completingSource.close();
            } catch (Throwable error) {
                failure = combine(failure, error);
            }

            try {
                Result result;
                synchronized (monitor) {
                    result = new Result(copyBytes(buffer, size), byteCount, phase, failure);
                }
                completingListener.complete(result);
            } catch (Throwable error) {
                failure = combine(failure, error);
            }
        } finally {
            synchronized (monitor) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                monitor.notifyAll();
            }
        }

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

    private static Throwable combine(Throwable earlier, Throwable later) {
        if (earlier == null) {
            return later;
        }
        if (earlier != later) {
            earlier.addSuppressed(later);
        }
        return earlier;
    }

    private static byte[] copyBytes(byte[] source, int length) {
        byte[] copy = new byte[length];
        for (int i = 0; i < length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
