import java.lang.Throwable;

interface ChunkSource {
    byte[] read();

    void close();
}

interface CompletionListener {
    void complete(Result result);
}

enum State {
    OPEN, READING, COMPLETING, CLOSED, FAILED
}

@Result.ThreadSafe
final class Result {
    @interface ThreadSafe {}

    private final byte[] data;
    private final int count;
    private final int phase;
    private final Throwable failure;

    Result(byte[] data, int count, int phase, Throwable failure) {
        this.data = data;
        this.count = count;
        this.phase = phase;
        this.failure = failure;
    }

    public byte[] data() {
        return data.clone();
    }

    public byte[] getData() {
        return data.clone();
    }

    public int count() {
        return count;
    }

    public int getCount() {
        return count;
    }

    public int phase() {
        return phase;
    }

    public int getPhase() {
        return phase;
    }

    public Throwable failure() {
        return failure;
    }

    public Throwable getFailure() {
        return failure;
    }
}

@Snapshot.ThreadSafe
final class Snapshot {
    @interface ThreadSafe {}

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

    public byte[] buffer() {
        return buffer.clone();
    }

    public byte[] getBuffer() {
        return buffer.clone();
    }

    public int byteCount() {
        return byteCount;
    }

    public int getByteCount() {
        return byteCount;
    }

    public int phase() {
        return phase;
    }

    public int getPhase() {
        return phase;
    }

    public State state() {
        return state;
    }

    public State getState() {
        return state;
    }
}

@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private final Object lock = new Object();

    // All fields below guarded by lock.
    private ChunkSource source;
    private CompletionListener listener;
    private State state = State.OPEN;
    private byte[] data = new byte[64];
    private int size = 0;
    private int phase = 0;
    private boolean closeRequested = false;

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
                if (closeRequested) {
                    return null;
                }
                if (state == State.READING) {
                    lock.wait();
                    continue;
                }
                state = State.READING;
                src = source;
                break;
            }
        }

        byte[] copy = null;
        RuntimeException readError = null;
        boolean done = false;
        try {
            try {
                byte[] chunk = src.read();
                if (chunk != null) {
                    copy = chunk.clone();
                }
            } catch (RuntimeException e) {
                readError = e;
            }

            boolean complete = false;
            synchronized (lock) {
                if (readError == null && copy != null) {
                    append(copy);
                }
                if (readError != null || copy == null || closeRequested) {
                    state = State.COMPLETING;
                    complete = true;
                } else {
                    state = State.OPEN;
                }
                done = true;
                lock.notifyAll();
            }
            if (complete) {
                complete(readError);
            }
            return copy == null ? null : copy.clone();
        } finally {
            if (!done) {
                synchronized (lock) {
                    if (state == State.READING) {
                        state = State.OPEN;
                    }
                    lock.notifyAll();
                }
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                return;
            }
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            closeRequested = true;
            state = State.COMPLETING;
            lock.notifyAll();
        }
        complete(null);
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            byte[] copy = new byte[size];
            System.arraycopy(data, 0, copy, 0, size);
            return new Snapshot(copy, size, phase, state);
        }
    }

    // Must hold lock.
    private void append(byte[] chunk) {
        int needed = size + chunk.length;
        if (needed < 0) {
            throw new IllegalStateException("buffer too large");
        }
        if (needed > data.length) {
            long cap = Math.max((long) data.length * 2, (long) needed);
            if (cap > Integer.MAX_VALUE - 8) {
                cap = Math.max(needed, Integer.MAX_VALUE - 8);
            }
            byte[] n = new byte[(int) cap];
            System.arraycopy(data, 0, n, 0, size);
            data = n;
        }
        System.arraycopy(chunk, 0, data, size, chunk.length);
        size = needed;
        int p = phase;
        for (int i = 0; i < chunk.length; i++) {
            p = (p + (chunk[i] & 0xFF)) % 360;
        }
        phase = p;
    }

    private static RuntimeException merge(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        if (next != current) {
            current.addSuppressed(next);
        }
        return current;
    }

    // Called by exactly one thread after state was set to COMPLETING.
    private void complete(RuntimeException initial) {
        RuntimeException err = initial;
        ChunkSource src;
        CompletionListener lis;
        synchronized (lock) {
            src = source;
            lis = listener;
        }
        boolean finished = false;
        try {
            try {
                src.close();
            } catch (RuntimeException e) {
                err = merge(err, e);
            }
            Result result;
            synchronized (lock) {
                byte[] copy = new byte[size];
                System.arraycopy(data, 0, copy, 0, size);
                result = new Result(copy, size, phase, err);
            }
            try {
                lis.complete(result);
            } catch (RuntimeException e) {
                err = merge(err, e);
            }
            finished = true;
        } finally {
            synchronized (lock) {
                state = (err != null || !finished) ? State.FAILED : State.CLOSED;
                source = null;
                listener = null;
                lock.notifyAll();
            }
        }
        if (err != null) {
            throw err;
        }
    }
}
