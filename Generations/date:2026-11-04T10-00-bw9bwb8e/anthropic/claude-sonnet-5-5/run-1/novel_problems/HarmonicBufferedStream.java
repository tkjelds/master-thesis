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
        OPEN, READING, COMPLETING, CLOSED, FAILED
    }

    public static final class Result {
        private final byte[] bytes;
        private final int count;
        private final int phase;
        private final Throwable failure;

        Result(byte[] bytes, int count, int phase, Throwable failure) {
            this.bytes = bytes;
            this.count = count;
            this.phase = phase;
            this.failure = failure;
        }

        public byte[] bytes() { return bytes.clone(); }
        public int count() { return count; }
        public int phase() { return phase; }
        public Throwable failure() { return failure; }
        public byte[] getBytes() { return bytes.clone(); }
        public int getCount() { return count; }
        public int getPhase() { return phase; }
        public Throwable getFailure() { return failure; }
    }

    public static final class Snapshot {
        private final byte[] buffer;
        private final int byteCount;
        private final int phase;
        private final State state;

        Snapshot(byte[] buffer, int byteCount, int phase, State state) {
            this.buffer = buffer;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }

        public byte[] buffer() { return buffer.clone(); }
        public int byteCount() { return byteCount; }
        public int phase() { return phase; }
        public State state() { return state; }
        public byte[] getBuffer() { return buffer.clone(); }
        public int getByteCount() { return byteCount; }
        public int getPhase() { return phase; }
        public State getState() { return state; }
    }

    private final Object lock = new Object();

    // All fields below guarded by lock.
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested;
    private byte[] data = new byte[64];
    private int size;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null || listener == null) {
            throw new NullPointerException();
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        ChunkSource src;
        synchronized (lock) {
            while (true) {
                if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                    return null;
                }
                if (state == State.READING) {
                    if (closeRequested) {
                        return null;
                    }
                    lock.wait();
                    continue;
                }
                // OPEN
                if (closeRequested) {
                    return null;
                }
                state = State.READING;
                src = source;
                break;
            }
        }

        byte[] chunk = null;
        Throwable failure = null;
        try {
            chunk = src.read();
        } catch (Throwable t) {
            failure = t;
        }
        byte[] copy = (chunk != null && failure == null) ? chunk.clone() : null;

        boolean complete = false;
        ChunkSource completeSource = null;
        CompletionListener completeListener = null;
        synchronized (lock) {
            if (failure == null && copy != null) {
                append(copy);
            }
            if (failure != null || copy == null || closeRequested) {
                state = State.COMPLETING;
                complete = true;
                completeSource = source;
                completeListener = listener;
            } else {
                state = State.OPEN;
            }
            lock.notifyAll();
        }
        if (complete) {
            runCompletion(completeSource, completeListener, failure);
        }
        return copy == null ? null : copy.clone();
    }

    public void close() {
        ChunkSource src;
        CompletionListener lst;
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
            src = source;
            lst = listener;
            lock.notifyAll();
        }
        runCompletion(src, lst, null);
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            byte[] copy = new byte[size];
            System.arraycopy(data, 0, copy, 0, size);
            return new Snapshot(copy, size, phase, state);
        }
    }

    // Caller holds lock.
    private void append(byte[] chunk) {
        int needed = size + chunk.length;
        if (needed > data.length) {
            int cap = data.length;
            while (cap < needed) {
                cap = cap * 2;
                if (cap < 0) {
                    cap = needed;
                    break;
                }
            }
            byte[] nd = new byte[cap];
            System.arraycopy(data, 0, nd, 0, size);
            data = nd;
        }
        System.arraycopy(chunk, 0, data, size, chunk.length);
        size = needed;
        int p = phase;
        for (int i = 0; i < chunk.length; i++) {
            p = (p + (chunk[i] & 0xFF)) % 360;
        }
        phase = p;
    }

    private static Throwable merge(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    // Called by the thread that claimed completion; state is COMPLETING.
    private void runCompletion(ChunkSource src, CompletionListener lst, Throwable error) {
        try {
            try {
                src.close();
            } catch (Throwable t) {
                error = merge(error, t);
            }
            Result result;
            synchronized (lock) {
                byte[] copy = new byte[size];
                System.arraycopy(data, 0, copy, 0, size);
                result = new Result(copy, size, phase, error);
            }
            try {
                lst.complete(result);
            } catch (Throwable t) {
                error = merge(error, t);
            }
        } catch (Throwable t) {
            error = merge(error, t);
        } finally {
            synchronized (lock) {
                state = (error != null) ? State.FAILED : State.CLOSED;
                source = null;
                listener = null;
                lock.notifyAll();
            }
        }
        if (error != null) {
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            if (error instanceof Error) {
                throw (Error) error;
            }
            throw new RuntimeException(error);
        }
    }
}
