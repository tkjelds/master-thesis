import java.lang.*;

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

    public byte[] getBytes() {
        return bytes.clone();
    }

    public int getByteCount() {
        return count;
    }

    public int getPhase() {
        return phase;
    }

    public Throwable getFailure() {
        return failure;
    }

    public byte[] bytes() {
        return getBytes();
    }

    public int count() {
        return count;
    }

    public int phase() {
        return phase;
    }

    public Throwable failure() {
        return failure;
    }
}

@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}


    public enum State {
        OPEN, READING, COMPLETING, CLOSED, FAILED
    }

    public static final class Snapshot {
        private final byte[] bytes;
        private final int count;
        private final int phase;
        private final State state;

        Snapshot(byte[] bytes, int count, int phase, State state) {
            this.bytes = bytes;
            this.count = count;
            this.phase = phase;
            this.state = state;
        }

        public byte[] getBytes() {
            return bytes.clone();
        }

        public int getByteCount() {
            return count;
        }

        public int getPhase() {
            return phase;
        }

        public State getState() {
            return state;
        }

        public byte[] bytes() {
            return getBytes();
        }

        public int count() {
            return count;
        }

        public int phase() {
            return phase;
        }

        public State state() {
            return state;
        }
    }

    private final Object lock = new Object();

    // All guarded by lock
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private boolean closeRequested = false;
    private byte[] buf = new byte[64];
    private int count = 0;
    private int phase = 0;

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
                if (state != State.OPEN && state != State.READING) {
                    return null; // COMPLETING, CLOSED, FAILED
                }
                if (state == State.OPEN) {
                    break;
                }
                lock.wait();
            }
            state = State.READING;
            src = source;
        }

        byte[] mine = null;
        Throwable failure = null;
        try {
            byte[] c = src.read();
            if (c != null) {
                mine = c.clone();
            }
        } catch (Throwable t) {
            failure = t;
        }

        ChunkSource cs = null;
        CompletionListener cl = null;
        boolean completing;
        synchronized (lock) {
            if (failure == null && mine != null) {
                try {
                    append(mine);
                } catch (RuntimeException e) {
                    failure = e;
                    mine = null;
                }
            }
            completing = failure != null || mine == null || closeRequested;
            if (completing) {
                state = State.COMPLETING;
                cs = source;
                cl = listener;
            } else {
                state = State.OPEN;
            }
            lock.notifyAll();
        }
        if (!completing) {
            return mine;
        }
        runCompletion(cs, cl, failure);
        return mine;
    }

    public void close() {
        ChunkSource cs;
        CompletionListener cl;
        synchronized (lock) {
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state != State.OPEN) {
                return;
            }
            state = State.COMPLETING;
            cs = source;
            cl = listener;
            lock.notifyAll();
        }
        runCompletion(cs, cl, null);
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            byte[] copy = new byte[count];
            System.arraycopy(buf, 0, copy, 0, count);
            return new Snapshot(copy, count, phase, state);
        }
    }

    // Must be called under lock.
    private void append(byte[] chunk) {
        int len = chunk.length;
        if (len == 0) {
            return;
        }
        long need = (long) count + len;
        if (need > Integer.MAX_VALUE - 8) {
            throw new IllegalStateException("buffer too large");
        }
        if (need > buf.length) {
            long cap = Math.max(need, Math.min((long) buf.length * 2, Integer.MAX_VALUE - 8));
            byte[] nb = new byte[(int) cap];
            System.arraycopy(buf, 0, nb, 0, count);
            buf = nb;
        }
        System.arraycopy(chunk, 0, buf, count, len);
        int p = phase;
        for (int i = 0; i < len; i++) {
            p = (p + (chunk[i] & 0xFF)) % 360;
        }
        count += len;
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

    // Called by the thread that holds the completion claim, outside the lock.
    private void runCompletion(ChunkSource cs, CompletionListener cl, Throwable error) {
        Throwable first = error;
        try {
            cs.close();
        } catch (Throwable t) {
            first = merge(first, t);
        }

        Result result;
        synchronized (lock) {
            byte[] copy = new byte[count];
            System.arraycopy(buf, 0, copy, 0, count);
            result = new Result(copy, count, phase, first);
        }

        try {
            cl.complete(result);
        } catch (Throwable t) {
            first = merge(first, t);
        }

        synchronized (lock) {
            state = (first != null) ? State.FAILED : State.CLOSED;
            source = null;
            listener = null;
            lock.notifyAll();
        }

        if (first != null) {
            if (first instanceof RuntimeException) {
                throw (RuntimeException) first;
            }
            if (first instanceof Error) {
                throw (Error) first;
            }
            throw new RuntimeException(first);
        }
    }
}
