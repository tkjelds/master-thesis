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

    private static final class Node {
        private final byte[] bytes;
        private Node next;

        private Node(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    private final Object monitor = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private Node head;
    private Node tail;
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
        ChunkSource currentSource;
        synchronized (monitor) {
            while (state == State.READING) {
                monitor.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            currentSource = source;
        }

        byte[] returned = null;
        Node node = null;
        int chunkPhase = 0;
        boolean eof = false;
        Throwable failure = null;
        try {
            byte[] chunk = currentSource.read();
            if (chunk == null) {
                eof = true;
            } else {
                byte[] retained = copy(chunk);
                returned = copy(retained);
                for (int i = 0; i < retained.length; i++) {
                    chunkPhase = (chunkPhase + (retained[i] & 255)) % 360;
                }
                if (retained.length != 0) {
                    node = new Node(retained);
                }
            }
        } catch (Throwable problem) {
            failure = problem;
        }

        boolean completing;
        synchronized (monitor) {
            if (failure == null && !eof) {
                if (byteCount + returned.length > 2147483647L) {
                    failure = new IllegalStateException("buffer exceeds maximum array size");
                } else {
                    if (node != null) {
                        if (tail == null) {
                            head = node;
                        } else {
                            tail.next = node;
                        }
                        tail = node;
                    }
                    byteCount += returned.length;
                    phase = (phase + chunkPhase) % 360;
                }
            }
            completing = failure != null || eof || closeRequested;
            state = completing ? State.COMPLETING : State.OPEN;
            monitor.notifyAll();
        }

        if (completing) {
            complete(failure);
        }
        return returned;
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
            state = State.COMPLETING;
            monitor.notifyAll();
        }
        complete(null);
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(bufferCopy(), byteCount, phase, state);
        }
    }

    private void complete(Throwable initialFailure) {
        Throwable failure = initialFailure;
        ChunkSource currentSource;
        CompletionListener currentListener;
        synchronized (monitor) {
            currentSource = source;
            currentListener = listener;
        }
        try {
            try {
                currentSource.close();
            } catch (Throwable problem) {
                failure = combine(failure, problem);
            }

            Result result;
            synchronized (monitor) {
                result = new Result(bufferCopy(), byteCount, phase, failure);
            }
            try {
                currentListener.complete(result);
            } catch (Throwable problem) {
                failure = combine(failure, problem);
            }
        } catch (Throwable problem) {
            failure = combine(failure, problem);
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

    private byte[] bufferCopy() {
        byte[] result = new byte[(int) byteCount];
        int offset = 0;
        for (Node node = head; node != null; node = node.next) {
            for (int i = 0; i < node.bytes.length; i++) {
                result[offset++] = node.bytes[i];
            }
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
}
