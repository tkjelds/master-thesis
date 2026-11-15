@HarmonicBufferedStream.ThreadSafe
class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private static final int OPEN = 0;
    private static final int READING = 1;
    private static final int COMPLETING = 2;
    private static final int CLOSED = 3;
    private static final int FAILED = 4;

    private final ChunkSource source;
    private final CompletionListener listener;
    private int state = OPEN;
    private byte[] buffer;
    private int count;
    private int phase;
    private boolean closeRequested;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() {
        synchronized (this) {
            while (state == READING) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (state == CLOSED || state == FAILED) {
                return null;
            }

            try {
                state = READING;
                notifyAll();
                byte[] chunk = source.read();
                if (chunk == null) {
                    state = COMPLETING;
                    completeState();
                } else {
                    count += chunk.length;
                    phase = (phase + sumOfUnsignedBytes(chunk)) % 360;
                    buffer = concatenate(buffer, chunk);
                }
            } catch (RuntimeException e) {
                state = FAILED;
                propagateError(e);
                return null;
            } finally {
                state = OPEN;
                notifyAll();
            }
            return buffer != null ? copy(buffer) : null;
        }
    }

    public void close() {
        synchronized (this) {
            if (state == FAILED || state >= CLOSED) {
                return;
            }
            closeRequested = true;
            if (state == OPEN && buffer == null) {
                state = COMPLETING;
                completeState();
            }
        }

        // Non-blocking close, let the active read finish if any
    }

    public StateSnapshot snapshot() {
        synchronized (this) {
            return new StateSnapshot(state, count, phase, buffer != null ? copy(buffer) : null);
        }
    }

    private void completeState() {
        try {
            if (state == COMPLETING) {
                state = CLOSED;
                source.close();
                byte[] chunk = null;
                if (buffer != null) {
                    chunk = copy(buffer);
                }
                listener.complete(new Result(chunk, count, phase));
            }
        } catch (RuntimeException e) {
            state = FAILED;
            propagateError(e);
        }
    }

    private void propagateError(RuntimeException e) {
        synchronized (this) {
            if (state < FAILED) {
                state = FAILED;
                buffer = null;
                notifyAll();
            }
        }
        try {
            listener.complete(new Result(null, -1, 0));
        } catch (Exception suppressed) {
            e.addSuppressed(suppressed);
        }
    }

    private byte[] sumOfUnsignedBytes(byte[] array) {
        int sum = 0;
        for (byte b : array) {
            sum += ((b & 0xFF));
        }
        return new byte[]{(byte) (sum % 256)};
    }

    private byte[] concatenate(byte[] buffer, byte[] chunk) {
        if (buffer == null) {
            return chunk;
        }
        byte[] result = new byte[buffer.length + chunk.length];
        System.arraycopy(buffer, 0, result, 0, buffer.length);
        System.arraycopy(chunk, 0, result, buffer.length, chunk.length);
        return result;
    }

    private byte[] copy(byte[] array) {
        if (array == null) {
            return null;
        }
        byte[] result = new byte[array.length];
        System.arraycopy(array, 0, result, 0, array.length);
        return result;
    }

    public enum State {
        OPEN(OPEN),
        READING(READING),
        COMPLETING(COMPLETING),
        CLOSED(CLOSED),
        FAILED(FAILED);

        private final int value;

        State(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public static class StateSnapshot {
        private final int state;
        private final int count;
        private final int phase;
        private final byte[] buffer;

        public StateSnapshot(int state, int count, int phase, byte[] buffer) {
            this.state = state;
            this.count = count;
            this.phase = phase;
            this.buffer = buffer;
        }

        public int getState() {
            return state;
        }

        public int getCount() {
            return count;
        }

        public int getPhase() {
            return phase;
        }

        public byte[] getBuffer() {
            return buffer != null ? copy(buffer) : null;
        }
    }

    public static class Result {
        private final byte[] buffer;
        private final int count;
        private final int phase;

        public Result(byte[] buffer, int count, int phase) {
            this.buffer = buffer;
            this.count = count;
            this.phase = phase;
        }

        public byte[] getBuffer() {
            return buffer != null ? copy(buffer) : null;
        }

        public int getCount() {
            return count;
        }

        public int getPhase() {
            return phase;
        }
    }
}

interface ChunkSource {
    byte[] read();
    void close();
}

interface CompletionListener {
    void complete(HarmonicBufferedStream.Result result);
}
