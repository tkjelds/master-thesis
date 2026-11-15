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
        OPEN, READING, COMPLETING, CLOSED, FAILED
    }

    public static final class Snapshot {
        public final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final State state;

        private Snapshot(byte[] bytes, long byteCount, int phase, State state) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }
    }

    public static final class Result {
        public final byte[] bytes;
        public final long byteCount;
        public final int phase;
        public final RuntimeException failure;

        private Result(byte[] bytes, long byteCount, int phase,
                       RuntimeException failure) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.failure = failure;
        }
    }

    private final Object monitor = new Object();
    private ChunkSource source;
    private CompletionListener listener;
    private byte[] buffer = new byte[0];
    private int length;
    private long byteCount;
    private int phase;
    private State state = State.OPEN;
    private boolean closeRequested;

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
        ChunkSource sourceToRead;

        synchronized (monitor) {
            while (state == State.READING) {
                monitor.wait();
            }
            if (state != State.OPEN) {
                return null;
            }
            state = State.READING;
            sourceToRead = source;
        }

        byte[] chunk = null;
        RuntimeException failure = null;
        try {
            byte[] read = sourceToRead.read();
            if (read != null) {
                chunk = read.clone();
            }
        } catch (RuntimeException exception) {
            failure = exception;
        }

        boolean complete;
        synchronized (monitor) {
            if (failure == null && chunk != null) {
                try {
                    appendLocked(chunk);
                } catch (RuntimeException exception) {
                    failure = exception;
                }
            }

            complete = failure != null || chunk == null || closeRequested;
            if (complete) {
                state = State.COMPLETING;
            } else {
                state = State.OPEN;
            }
            monitor.notifyAll();
        }

        if (complete) {
            failure = finishCompletion(failure);
            if (failure != null) {
                throw failure;
            }
        }

        return chunk;
    }

    public void close() {
        boolean complete = false;

        synchronized (monitor) {
            if (state == State.OPEN) {
                state = State.COMPLETING;
                complete = true;
                monitor.notifyAll();
            } else if (state == State.READING) {
                closeRequested = true;
            }
        }

        if (complete) {
            RuntimeException failure = finishCompletion(null);
            if (failure != null) {
                throw failure;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(copyBufferLocked(), byteCount, phase, state);
        }
    }

    private void appendLocked(byte[] chunk) {
        if (chunk.length > Integer.MAX_VALUE - length) {
            throw new IllegalStateException("Buffered stream exceeds array capacity");
        }

        int newLength = length + chunk.length;
        if (newLength > buffer.length) {
            int newCapacity = buffer.length == 0 ? 16 : buffer.length;
            while (newCapacity < newLength) {
                if (newCapacity > Integer.MAX_VALUE / 2) {
                    newCapacity = Integer.MAX_VALUE;
                } else {
                    newCapacity *= 2;
                }
            }

            byte[] expanded = new byte[newCapacity];
            for (int i = 0; i < length; i++) {
                expanded[i] = buffer[i];
            }
            buffer = expanded;
        }

        for (int i = 0; i < chunk.length; i++) {
            buffer[length + i] = chunk[i];
        }

        for (int i = 0; i < chunk.length; i++) {
            phase = (phase + (chunk[i] & 0xff)) % 360;
        }
        length = newLength;
        byteCount += chunk.length;
    }

    private byte[] copyBufferLocked() {
        byte[] copy = new byte[length];
        for (int i = 0; i < length; i++) {
            copy[i] = buffer[i];
        }
        return copy;
    }

    private RuntimeException finishCompletion(RuntimeException failure) {
        ChunkSource sourceToClose;
        CompletionListener listenerToCall;
        byte[] bytes;
        long count;
        int finalPhase;

        synchronized (monitor) {
            sourceToClose = source;
            listenerToCall = listener;
            bytes = copyBufferLocked();
            count = byteCount;
            finalPhase = phase;
        }

        try {
            try {
                sourceToClose.close();
            } catch (RuntimeException exception) {
                failure = combine(failure, exception);
            }

            Result result = new Result(bytes, count, finalPhase, failure);
            try {
                listenerToCall.complete(result);
            } catch (RuntimeException exception) {
                failure = combine(failure, exception);
            }
        } finally {
            synchronized (monitor) {
                source = null;
                listener = null;
                state = failure == null ? State.CLOSED : State.FAILED;
                monitor.notifyAll();
            }
        }

        return failure;
    }

    private static RuntimeException combine(RuntimeException earlier,
                                             RuntimeException later) {
        if (earlier == null) {
            return later;
        }
        if (earlier != later) {
            earlier.addSuppressed(later);
        }
        return earlier;
    }
}
