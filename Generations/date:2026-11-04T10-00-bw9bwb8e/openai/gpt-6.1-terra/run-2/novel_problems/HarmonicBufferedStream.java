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

    public static final class Snapshot {
        private final byte[][] chunks;
        private final long byteCount;
        private final int phase;
        private final State state;

        private Snapshot(byte[][] chunks, long byteCount, int phase, State state) {
            this.chunks = chunks;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }

        public byte[][] getChunks() {
            return copyChunks(chunks);
        }

        public byte[] getBuffer() {
            return flatten(chunks, byteCount);
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
        private final byte[][] chunks;
        private final long byteCount;
        private final int phase;
        private final Throwable failure;

        private Result(byte[][] chunks, long byteCount, int phase, Throwable failure) {
            this.chunks = chunks;
            this.byteCount = byteCount;
            this.phase = phase;
            this.failure = failure;
        }

        public byte[][] getChunks() {
            return copyChunks(chunks);
        }

        public byte[] getBuffer() {
            return flatten(chunks, byteCount);
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

    private static final class Node {
        private final byte[] bytes;
        private Node next;

        private Node(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    private final Object lock = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private Node head;
    private Node tail;
    private int chunkCount;
    private long byteCount;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null || listener == null) {
            throw new IllegalArgumentException("Dependencies must not be null");
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

        byte[] chunk = null;
        byte[] delivered = null;
        Node node = null;
        int chunkPhase = 0;
        Throwable failure = null;

        try {
            chunk = activeSource.read();
            if (chunk != null) {
                byte[] retained = copyBytes(chunk);
                delivered = copyBytes(retained);
                node = new Node(retained);
                for (int i = 0; i < retained.length; i++) {
                    chunkPhase = (chunkPhase + (retained[i] & 255)) % 360;
                }
            }
        } catch (Throwable t) {
            failure = t;
        }

        boolean completing;
        synchronized (lock) {
            if (failure == null && chunk != null) {
                if (byteCount > Long.MAX_VALUE - node.bytes.length
                        || chunkCount == Integer.MAX_VALUE) {
                    failure = new IllegalStateException("Buffer size limit exceeded");
                } else {
                    if (tail == null) {
                        head = node;
                    } else {
                        tail.next = node;
                    }
                    tail = node;
                    chunkCount++;
                    byteCount += node.bytes.length;
                    phase = (phase + chunkPhase) % 360;
                }
            }

            completing = failure != null || chunk == null || closeRequested;
            state = completing ? State.COMPLETING : State.OPEN;
            lock.notifyAll();
        }

        if (completing) {
            finishCompletion(failure);
        }
        return delivered;
    }

    public void close() {
        boolean completing = false;
        synchronized (lock) {
            if (state == State.OPEN) {
                state = State.COMPLETING;
                completing = true;
                lock.notifyAll();
            } else if (state == State.READING) {
                closeRequested = true;
            }
        }
        if (completing) {
            finishCompletion(null);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copyBufferedChunks(), byteCount, phase, state);
        }
    }

    private void finishCompletion(Throwable initialFailure) {
        Throwable failure = initialFailure;
        ChunkSource completingSource;
        CompletionListener completingListener;

        synchronized (lock) {
            completingSource = source;
            completingListener = listener;
        }

        try {
            try {
                completingSource.close();
            } catch (Throwable t) {
                failure = combine(failure, t);
            }

            Result result;
            synchronized (lock) {
                result = new Result(copyBufferedChunks(), byteCount, phase, failure);
            }

            try {
                completingListener.complete(result);
            } catch (Throwable t) {
                failure = combine(failure, t);
            }
        } catch (Throwable t) {
            failure = combine(failure, t);
        } finally {
            synchronized (lock) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                lock.notifyAll();
            }
        }

        if (failure != null) {
            HarmonicBufferedStream.<RuntimeException>throwUnchecked(failure);
        }
    }

    private byte[][] copyBufferedChunks() {
        byte[][] result = new byte[chunkCount][];
        Node current = head;
        for (int i = 0; i < result.length; i++) {
            result[i] = copyBytes(current.bytes);
            current = current.next;
        }
        return result;
    }

    private static byte[] copyBytes(byte[] bytes) {
        byte[] copy = new byte[bytes.length];
        System.arraycopy(bytes, 0, copy, 0, bytes.length);
        return copy;
    }

    private static byte[][] copyChunks(byte[][] chunks) {
        byte[][] copy = new byte[chunks.length][];
        for (int i = 0; i < chunks.length; i++) {
            copy[i] = copyBytes(chunks[i]);
        }
        return copy;
    }

    private static byte[] flatten(byte[][] chunks, long byteCount) {
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalStateException("Buffer is too large for one array");
        }
        byte[] result = new byte[(int) byteCount];
        int offset = 0;
        for (int i = 0; i < chunks.length; i++) {
            System.arraycopy(chunks[i], 0, result, offset, chunks[i].length);
            offset += chunks[i].length;
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

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
