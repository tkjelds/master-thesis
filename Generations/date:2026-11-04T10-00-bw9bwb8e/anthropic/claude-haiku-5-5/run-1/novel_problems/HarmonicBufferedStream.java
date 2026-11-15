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
        private final int byteCount;
        private final int phase;
        private final RuntimeException readFailure;
        private final RuntimeException closeFailure;

        Result(byte[] bytes, int byteCount, int phase,
               RuntimeException readFailure, RuntimeException closeFailure) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        public byte[] bytes() {
            return copyBytes(bytes, 0, bytes.length);
        }

        public int byteCount() {
            return byteCount;
        }

        public int phase() {
            return phase;
        }

        public RuntimeException readFailure() {
            return readFailure;
        }

        public RuntimeException closeFailure() {
            return closeFailure;
        }
    }

    public static final class Snapshot {
        private final byte[] bytes;
        private final int byteCount;
        private final int phase;
        private final State state;

        Snapshot(byte[] bytes, int byteCount, int phase, State state) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }

        public byte[] bytes() {
            return copyBytes(bytes, 0, bytes.length);
        }

        public int byteCount() {
            return byteCount;
        }

        public int phase() {
            return phase;
        }

        public State state() {
            return state;
        }
    }

    private final Object lock = new Object();
    private ChunkSource source;          // guarded by lock
    private CompletionListener listener; // guarded by lock
    private State state = State.OPEN;    // guarded by lock
    private Thread reader;               // guarded by lock
    private boolean closeRequested;      // guarded by lock
    private byte[] buffer = new byte[64];// guarded by lock
    private int count;                   // guarded by lock
    private int phase;                   // guarded by lock

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

    /**
     * Returns a detached chunk, or null after completion. Waits interruptibly while
     * another read is in flight. Never waits during COMPLETING.
     */
    public byte[] readChunk() throws InterruptedException {
        ChunkSource src;
        synchronized (lock) {
            while (true) {
                if (state == State.OPEN) {
                    state = State.READING;
                    reader = Thread.currentThread();
                    src = source;
                    break;
                }
                if (state != State.READING) {
                    return null;
                }
                if (reader == Thread.currentThread()) {
                    throw new IllegalStateException("readChunk reentered from source.read");
                }
                lock.wait();
            }
        }

        byte[] chunk = null;
        RuntimeException failure = null;
        boolean ok = false;
        try {
            chunk = src.read();
            ok = true;
        } catch (RuntimeException e) {
            failure = e;
            ok = true;
        } finally {
            if (!ok) {
                abandonRead();
            }
        }

        byte[] detached = null;
        boolean finish;
        synchronized (lock) {
            reader = null;
            if (failure == null && chunk != null) {
                appendLocked(chunk);
                detached = copyBytes(chunk, 0, chunk.length);
            }
            finish = failure != null || chunk == null || closeRequested;
            if (finish) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
                lock.notifyAll();
            }
        }

        if (finish) {
            runCompletion(failure);
        }
        return detached;
    }

    /**
     * Nonblocking if a read is in flight: records a close request and lets the reader complete.
     * If idle, completes the stream on the calling thread.
     */
    public void close() {
        synchronized (lock) {
            if (state == State.OPEN) {
                state = State.COMPLETING;
            } else if (state == State.READING) {
                closeRequested = true;
                return;
            } else {
                return;
            }
        }
        runCompletion(null);
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copyBytes(buffer, 0, count), count, phase, state);
        }
    }

    // Must be called with lock NOT held; caller owns the completion claim (state == COMPLETING).
    private void runCompletion(RuntimeException readFailure) {
        ChunkSource src;
        CompletionListener lst;
        synchronized (lock) {
            src = source;
            lst = listener;
        }

        RuntimeException closeFailure = null;
        try {
            src.close();
        } catch (RuntimeException e) {
            closeFailure = e;
        }

        Result result;
        synchronized (lock) {
            result = new Result(copyBytes(buffer, 0, count), count, phase, readFailure, closeFailure);
        }

        RuntimeException listenerFailure = null;
        try {
            lst.complete(result);
        } catch (RuntimeException e) {
            listenerFailure = e;
        }

        synchronized (lock) {
            state = (readFailure != null || closeFailure != null || listenerFailure != null)
                    ? State.FAILED
                    : State.CLOSED;
            source = null;
            listener = null;
            lock.notifyAll();
        }

        RuntimeException primary = null;
        RuntimeException[] errors = { readFailure, closeFailure, listenerFailure };
        for (RuntimeException e : errors) {
            if (e == null) {
                continue;
            }
            if (primary == null) {
                primary = e;
            } else if (e != primary) {
                primary.addSuppressed(e);
            }
        }
        if (primary != null) {
            throw primary;
        }
    }

    private void abandonRead() {
        synchronized (lock) {
            reader = null;
            state = State.FAILED;
            source = null;
            listener = null;
            lock.notifyAll();
        }
    }

    // Must be called with lock held.
    private void appendLocked(byte[] chunk) {
        int len = chunk.length;
        if (len > 0) {
            long needed = (long) count + len;
            if (needed > buffer.length) {
                long cap = Math.max(1L, buffer.length);
                while (cap < needed) {
                    cap *= 2;
                }
                byte[] grown = new byte[(int) Math.min(cap, Integer.MAX_VALUE - 8)];
                System.arraycopy(buffer, 0, grown, 0, count);
                buffer = grown;
            }
            System.arraycopy(chunk, 0, buffer, count, len);
            count += len;
        }
        for (int i = 0; i < len; i++) {
            phase = (phase + (chunk[i] & 0xFF)) % 360;
        }
    }

    private static byte[] copyBytes(byte[] src, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(src, offset, out, 0, length);
        return out;
    }
}
