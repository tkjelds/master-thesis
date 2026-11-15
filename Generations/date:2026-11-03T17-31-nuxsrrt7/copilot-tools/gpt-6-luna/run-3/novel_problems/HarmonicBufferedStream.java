interface ChunkSource {
    byte[] read();

    void close();
}

interface CompletionListener {
    void complete(HarmonicBufferedStream.Result result);
}

@HarmonicBufferedStream.ThreadSafe
public final class HarmonicBufferedStream {
    @interface ThreadSafe {}

    public enum State {
        OPEN,
        READING,
        COMPLETING,
        CLOSED,
        FAILED
    }

    public static final class Snapshot {
        public final byte[] buffer;
        public final long byteCount;
        public final int phase;
        public final State state;

        private Snapshot(byte[] buffer, long byteCount, int phase, State state) {
            this.buffer = buffer;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }
    }

    public static final class Result {
        public final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final Throwable failure;

        private Result(byte[] bytes, long byteCount, int phase, Throwable failure) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.failure = failure;
        }
    }

    private final Object monitor = new Object();
    private byte[] buffer = new byte[0];
    private int bufferLength;
    private long byteCount;
    private int phase;
    private State state = State.OPEN;
    private boolean closeRequested;
    private volatile ChunkSource source;
    private volatile CompletionListener listener;

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
        synchronized (monitor) {
            while (state == State.READING) {
                monitor.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
        }

        byte[] raw = null;
        byte[] chunk = null;
        Throwable failure = null;
        try {
            raw = source.read();
            if (raw != null) {
                chunk = copy(raw);
            }
        } catch (Throwable t) {
            failure = t;
        }

        boolean completing;
        synchronized (monitor) {
            if (failure == null && chunk != null) {
                try {
                    append(chunk);
                } catch (Throwable t) {
                    failure = t;
                }
            }

            completing = failure != null || raw == null || closeRequested;
            if (completing) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            monitor.notifyAll();
        }

        if (completing) {
            failure = finishCompletion(failure);
            if (failure != null) {
                throwUnchecked(failure);
            }
        }
        return chunk;
    }

    public void close() {
        boolean completeHere = false;
        synchronized (monitor) {
            if (state == State.READING) {
                closeRequested = true;
                return;
            }
            if (state != State.OPEN) {
                return;
            }
            state = State.COMPLETING;
            completeHere = true;
            monitor.notifyAll();
        }

        if (completeHere) {
            Throwable failure = finishCompletion(null);
            if (failure != null) {
                throwUnchecked(failure);
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(copy(buffer, bufferLength), byteCount, phase, state);
        }
    }

    private void append(byte[] chunk) {
        int required = bufferLength + chunk.length;
        if (required < 0) {
            throw new OutOfMemoryError("Buffered stream is too large");
        }
        if (required > buffer.length) {
            int capacity = buffer.length == 0 ? 16 : buffer.length;
            while (capacity < required) {
                int doubled = capacity << 1;
                if (doubled <= capacity) {
                    capacity = required;
                    break;
                }
                capacity = doubled;
            }
            byte[] expanded = new byte[capacity];
            System.arraycopy(buffer, 0, expanded, 0, bufferLength);
            buffer = expanded;
        }

        System.arraycopy(chunk, 0, buffer, bufferLength, chunk.length);
        bufferLength = required;
        byteCount += chunk.length;
        for (int i = 0; i < chunk.length; i++) {
            phase = (phase + (chunk[i] & 0xff)) % 360;
        }
    }

    private Throwable finishCompletion(Throwable failure) {
        ChunkSource sourceToClose;
        CompletionListener listenerToCall;
        synchronized (monitor) {
            sourceToClose = source;
            listenerToCall = listener;
        }

        try {
            sourceToClose.close();
        } catch (Throwable t) {
            failure = combine(failure, t);
        }

        Result result;
        synchronized (monitor) {
            result = new Result(copy(buffer, bufferLength), byteCount, phase, failure);
        }

        try {
            listenerToCall.complete(result);
        } catch (Throwable t) {
            failure = combine(failure, t);
        } finally {
            synchronized (monitor) {
                state = failure == null ? State.CLOSED : State.FAILED;
                source = null;
                listener = null;
                monitor.notifyAll();
            }
        }
        return failure;
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

    private static byte[] copy(byte[] bytes) {
        return copy(bytes, bytes.length);
    }

    private static byte[] copy(byte[] bytes, int length) {
        byte[] result = new byte[length];
        System.arraycopy(bytes, 0, result, 0, length);
        return result;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new AssertionError(failure);
    }
}
