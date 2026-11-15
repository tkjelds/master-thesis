@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
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
        private final int phase;
        private final RuntimeException failure;

        Result(byte[] bytes, int phase, RuntimeException failure) {
            this.bytes = bytes;
            this.phase = phase;
            this.failure = failure;
        }

        public byte[] bytes() {
            byte[] copy = new byte[bytes.length];
            System.arraycopy(bytes, 0, copy, 0, bytes.length);
            return copy;
        }

        public int byteCount() {
            return bytes.length;
        }

        public int phase() {
            return phase;
        }

        public RuntimeException failure() {
            return failure;
        }
    }

    public static final class Snapshot {
        private final byte[] bytes;
        private final int phase;
        private final State state;

        Snapshot(byte[] bytes, int phase, State state) {
            this.bytes = bytes;
            this.phase = phase;
            this.state = state;
        }

        public byte[] bytes() {
            byte[] copy = new byte[bytes.length];
            System.arraycopy(bytes, 0, copy, 0, bytes.length);
            return copy;
        }

        public int byteCount() {
            return bytes.length;
        }

        public int phase() {
            return phase;
        }

        public State state() {
            return state;
        }
    }

    private final Object lock = new Object();

    // All fields below are guarded by lock.
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private byte[] buffer = new byte[0];
    private int count = 0;
    private int phase = 0;
    private boolean closeRequested = false;
    private Thread activeReader = null;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        if (source == null || listener == null) {
            throw new NullPointerException("source and listener must not be null");
        }
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (closeRequested || state == State.COMPLETING
                        || state == State.CLOSED || state == State.FAILED) {
                    return null;
                }
                if (state == State.OPEN) {
                    state = State.READING;
                    activeReader = Thread.currentThread();
                    break;
                }
                // state == READING
                if (activeReader == Thread.currentThread()) {
                    // Reentrant call from within the source's read; cannot wait on itself.
                    return null;
                }
                lock.wait();
            }
        }
        return performRead();
    }

    public void close() {
        boolean completeNow = false;
        synchronized (lock) {
            if (state == State.OPEN) {
                state = State.COMPLETING;
                lock.notifyAll();
                completeNow = true;
            } else if (state == State.READING) {
                closeRequested = true;
            }
            // COMPLETING, CLOSED, FAILED, or already requested: no-op
        }
        if (completeNow) {
            finishCompletion(null);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            byte[] copy = new byte[count];
            System.arraycopy(buffer, 0, copy, 0, count);
            return new Snapshot(copy, phase, state);
        }
    }

    // Called by the thread that claimed the READING state. Source is invoked outside the lock.
    private byte[] performRead() {
        byte[] chunk = null;
        RuntimeException readErr = null;
        try {
            chunk = source.read();
        } catch (RuntimeException e) {
            readErr = e;
        }

        byte[] retained = null;
        byte[] returned = null;
        if (chunk != null && readErr == null) {
            retained = chunk.clone();
            returned = chunk.clone();
        }

        synchronized (lock) {
            activeReader = null;
            if (retained != null) {
                appendLocked(retained);
            }
            boolean stop = readErr != null || chunk == null || closeRequested;
            if (!stop) {
                state = State.OPEN;
                lock.notifyAll();
                return returned;
            }
            state = State.COMPLETING;
            lock.notifyAll();
        }

        finishCompletion(readErr);
        return returned;
    }

    // Must be called with lock held.
    private void appendLocked(byte[] bytes) {
        int needed = count + bytes.length;
        if (needed > buffer.length) {
            int capacity = Math.max(needed, buffer.length * 2);
            byte[] grown = new byte[capacity];
            System.arraycopy(buffer, 0, grown, 0, count);
            buffer = grown;
        }
        System.arraycopy(bytes, 0, buffer, count, bytes.length);
        count = needed;
        int p = phase;
        for (byte b : bytes) {
            p = (p + (b & 0xFF)) % 360;
        }
        phase = p;
    }

    // Caller has already moved state to COMPLETING (claim held by this thread).
    private void finishCompletion(RuntimeException readErr) {
        ChunkSource src;
        CompletionListener lst;
        byte[] bytes;
        int ph;
        synchronized (lock) {
            src = source;
            lst = listener;
            bytes = new byte[count];
            System.arraycopy(buffer, 0, bytes, 0, count);
            ph = phase;
        }

        RuntimeException closeErr = null;
        RuntimeException listenerErr = null;
        try {
            try {
                if (src != null) {
                    src.close();
                }
            } catch (RuntimeException e) {
                closeErr = e;
            }

            RuntimeException reported = readErr != null ? readErr : closeErr;
            Result result = new Result(bytes, ph, reported);

            try {
                if (lst != null) {
                    lst.complete(result);
                }
            } catch (RuntimeException e) {
                listenerErr = e;
            }
        } finally {
            boolean failed = readErr != null || closeErr != null || listenerErr != null;
            synchronized (lock) {
                source = null;
                listener = null;
                activeReader = null;
                state = failed ? State.FAILED : State.CLOSED;
                lock.notifyAll();
            }
        }

        RuntimeException primary = firstError(readErr, closeErr, listenerErr);
        if (primary != null) {
            throw primary;
        }
    }

    private static RuntimeException firstError(RuntimeException first,
                                               RuntimeException second,
                                               RuntimeException third) {
        RuntimeException[] errs = {first, second, third};
        RuntimeException primary = null;
        for (RuntimeException e : errs) {
            if (e == null) {
                continue;
            }
            if (primary == null) {
                primary = e;
            } else if (e != primary) {
                primary.addSuppressed(e);
            }
        }
        return primary;
    }
}
