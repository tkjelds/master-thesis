@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private static final int OPEN = 0;
    private static final int READING = 1;
    private static final int COMPLETING = 2;
    private static final int CLOSED = 3;
    private static final int FAILED = 4;

    private ChunkSource source;
    private CompletionListener listener;
    private byte[] buffer;
    private int byteCount;
    private int phase;
    private int state;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
        this.buffer = new byte[0];
        this.byteCount = 0;
        this.phase = 0;
        this.state = OPEN;
    }

    public byte[] readChunk() throws InterruptedException {
        synchronized (this) {
            while (state == READING || state == COMPLETING) {
                wait();
            }
            if (state == FAILED) {
                throw new IllegalStateException("Stream is in failed state");
            }
            if (state == CLOSED) {
                return null;
            }

            state = READING;
        }

        try {
            byte[] chunk = source.read();
            if (chunk != null) {
                synchronized (this) {
                    byte[] newBuffer = new byte[buffer.length + chunk.length];
                    System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                    System.arraycopy(chunk, 0, newBuffer, buffer.length, chunk.length);
                    buffer = newBuffer;
                    byteCount += chunk.length;

                    int phaseDelta = 0;
                    for (byte b : chunk) {
                        phaseDelta += (b & 0xFF); // Treat as unsigned
                    }
                    phase = (phase + phaseDelta) % 360;
                }

                return chunk;
            } else {
                synchronized (this) {
                    state = COMPLETING;
                }

                try {
                    complete(new Result(buffer, byteCount, phase));
                } finally {
                    synchronized (this) {
                        state = CLOSED;
                        notifyAll();
                    }
                }

                return null;
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                state = FAILED;
                notifyAll();
            }

            throw e;
        } finally {
            synchronized (this) {
                state = OPEN;
                notifyAll();
            }
        }
    }

    public void close() {
        synchronized (this) {
            if (state != OPEN && state != COMPLETING && state != CLOSED) {
                return;
            }

            if (state == OPEN) {
                state = COMPLETING;
            } else {
                closeRequest();
            }

            try {
                while (state == COMPLETING) {
                    wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (state == FAILED) {
                throw new IllegalStateException("Stream is in failed state");
            }
        }

        try {
            complete(new Result(buffer, byteCount, phase));
        } finally {
            listener = null;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(buffer, byteCount, phase, state);
        }
    }

    private void complete(Result result) {
        try {
            listener.complete(result);
        } catch (RuntimeException e) {
            if (!Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }

            synchronized (this) {
                state = FAILED;
                notifyAll();
            }
        } finally {
            source.close();
        }
    }

    private void closeRequest() {
        state = CLOSED;
        notifyAll();
    }

    public static class Result {
        private final byte[] buffer;
        private final int byteCount;
        private final int phase;
        private final Throwable error;

        public Result(byte[] buffer, int byteCount, int phase) {
            this.buffer = buffer;
            this.byteCount = byteCount;
            this.phase = phase;
            this.error = null;
        }

        public Result(byte[] buffer, int byteCount, int phase, Throwable error) {
            this.buffer = buffer;
            this.byteCount = byteCount;
            this.phase = phase;
            this.error = error;
        }

        public byte[] getBuffer() {
            return buffer;
        }

        public int getCount() {
            return byteCount;
        }

        public int getPhase() {
            return phase;
        }

        public Throwable error() {
            return error;
        }
    }

    public static class Snapshot {
        private final byte[] buffer;
        private final int byteCount;
        private final int phase;
        private final int state;

        public Snapshot(byte[] buffer, int byteCount, int phase, int state) {
            this.buffer = new byte[buffer.length];
            System.arraycopy(buffer, 0, this.buffer, 0, buffer.length);
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }

        public byte[] getBuffer() {
            return buffer;
        }

        public int getCount() {
            return byteCount;
        }

        public int getPhase() {
            return phase;
        }

        public boolean isClosed() {
            return state == CLOSED || state == FAILED;
        }
    }

    public interface ChunkSource {
        byte[] read();
        void close();
    }

    public interface CompletionListener {
        void complete(Result result);
    }
}
