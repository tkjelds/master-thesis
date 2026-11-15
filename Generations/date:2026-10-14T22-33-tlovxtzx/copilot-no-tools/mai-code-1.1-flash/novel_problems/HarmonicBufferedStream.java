@HarmonicBufferedStream.ThreadSafe
Unable to run `javac -version` because the environment requires interactive permission approval, which is unavailable.
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
        OPEN,
        READING,
        COMPLETING,
        CLOSED,
        FAILED
    }

    public static final class Result {
        public final byte[] bytes;
        public final int count;
        public final int phase;
        public final Throwable failure;

        public Result(byte[] bytes, int count, int phase, Throwable failure) {
            this.bytes = bytes;
            this.count = count;
            this.phase = phase;
            this.failure = failure;
        }
    }

    public static final class Snapshot {
        public final byte[] buffer;
        public final int count;
        public final int phase;
        public final State state;

        public Snapshot(byte[] buffer, int count, int phase, State state) {
            this.buffer = buffer;
            this.count = count;
            this.phase = phase;
            this.state = state;
        }
    }

    private ChunkSource source;
    private CompletionListener listener;
    private final Object lock = new Object();

    private State state = State.OPEN;
    private byte[] buffer = new byte[0];
    private int count = 0;
    private int phase = 0;
    private Throwable failure = null;
    private boolean closeRequested = false;
    private boolean sourceClosed = false;

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
        for (;;) {
            synchronized (lock) {
                while (state == State.READING) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                if (state == State.OPEN) {
                    if (closeRequested) {
                        return null;
                    }
                    state = State.READING;
                    break;
                }
                if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                    return null;
                }
            }
        }

        byte[] chunk = null;
        Throwable readFailure = null;
        try {
            chunk = source.read();
        } catch (RuntimeException e) {
            readFailure = e;
            captureFailure(e);
        }

        boolean shouldComplete = false;
        byte[] returned = null;
        synchronized (lock) {
            if (readFailure != null) {
                state = State.OPEN;
                notifyAll();
                shouldComplete = true;
            } else if (chunk == null) {
                state = State.OPEN;
                notifyAll();
                shouldComplete = true;
            } else {
                byte[] retained = copyBytes(chunk);
                appendLocked(retained);
                returned = copyBytes(retained);
                state = State.OPEN;
                if (closeRequested) {
                    shouldComplete = true;
                }
                notifyAll();
            }
        }

        if (shouldComplete) {
            Throwable thrown = completeStream(readFailure);
            if (thrown != null) {
                throw thrown;
            }
        }

        return returned;
    }

    public void close() {
        boolean shouldComplete = false;
        synchronized (lock) {
            if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                return;
            }
            closeRequested = true;
            if (state == State.OPEN) {
                shouldComplete = true;
            }
        }
        if (shouldComplete) {
            Throwable thrown = completeStream(null);
            if (thrown != null) {
                throw thrown;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(copyBytes(buffer, count), count, phase, state);
        }
    }

    private Throwable completeStream(Throwable callerFailure) {
        boolean claimed = false;
        synchronized (lock) {
            if (state == State.COMPLETING || state == State.CLOSED || state == State.FAILED) {
                return callerFailure;
            }
            if (state == State.READING) {
                return callerFailure;
            }
            state = State.COMPLETING;
            claimed = true;
        }

        if (!claimed) {
            return callerFailure;
        }

        boolean invokeClose = false;
        synchronized (lock) {
            if (!sourceClosed) {
                sourceClosed = true;
                invokeClose = true;
            }
        }

        Throwable closeFailure = null;
        if (invokeClose) {
            try {
                source.close();
            } catch (RuntimeException e) {
                closeFailure = e;
                captureFailure(e);
            }
        }

        Result result;
        synchronized (lock) {
            result = new Result(copyBytes(buffer, count), count, phase, failure);
        }

        Throwable listenerFailure = null;
        try {
            listener.complete(result);
        } catch (RuntimeException e) {
            listenerFailure = e;
            captureFailure(e);
        }

        Throwable finalFailure;
        synchronized (lock) {
            finalFailure = failure;
            if (finalFailure != null) {
                state = State.FAILED;
            } else {
                state = State.CLOSED;
            }
            notifyAll();
            source = null;
            listener = null;
        }

        if (callerFailure != null) {
            return callerFailure;
        }
        if (finalFailure != null) {
            return finalFailure;
        }
        if (closeFailure != null) {
            return closeFailure;
        }
        if (listenerFailure != null) {
            return listenerFailure;
        }
        return null;
    }

    private void captureFailure(Throwable error) {
        if (error == null) {
            return;
        }
        synchronized (lock) {
            if (failure == null) {
                failure = error;
            } else if (failure != error) {
                failure.addSuppressed(error);
            }
        }
    }

    private void appendLocked(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        int oldCount = count;
        int newCount = oldCount + chunk.length;
        byte[] array = new byte[newCount];
        if (oldCount > 0) {
            System.arraycopy(buffer, 0, array, 0, oldCount);
        }
        System.arraycopy(chunk, 0, array, oldCount, chunk.length);
        buffer = array;
        count = newCount;

        int newPhase = phase;
        for (int i = 0; i < chunk.length; i++) {
            newPhase = (newPhase + (chunk[i] & 0xff)) % 360;
        }
        phase = newPhase;
    }

    private static byte[] copyBytes(byte[] source) {
        if (source == null) {
            return null;
        }
        byte[] copy = new byte[source.length];
        if (source.length > 0) {
            System.arraycopy(source, 0, copy, 0, source.length);
        }
        return copy;
    }
}
