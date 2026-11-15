@HarmonicBufferedStream.ThreadSafe
class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private static final int OPEN = 0;
    private static final int READING = 1;
    private static final int COMPLETING = 2;
    private static final int CLOSED = 3;
    private static final int FAILED = 4;

    private int state;
    private byte[] buffer;
    private int count;
    private int phase;
    private ChunkSource source;
    private CompletionListener listener;
    private byte[] chunkToRelease;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.state = OPEN;
        this.source = source;
        this.listener = listener;
        this.buffer = new byte[0];
    }

    public synchronized byte[] readChunk() throws InterruptedException {
        while (state == READING) {
            wait();
        }
        if (state != OPEN) {
            return null;
        }
        state = READING;
        notifyAll();
        try {
            byte[] chunk = source.read();
            if (chunk == null) {
                complete(new Result(buffer, count, phase));
            } else {
                byte[] newBuffer = new byte[buffer.length + chunk.length];
                System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                System.arraycopy(chunk, 0, newBuffer, buffer.length, chunk.length);
                buffer = newBuffer;
                count += chunk.length;
                phase = (phase + sum(chunk)) % 360;
            }
        } catch (RuntimeException e) {
            state = FAILED;
            notifyAll();
            throw e;
        } finally {
            state = COMPLETING;
            notifyAll();
        }
        return chunkToRelease == null ? null : releaseChunk(chunkToRelease);
    }

    public synchronized void close() {
        if (state == CLOSED || state == FAILED) {
            return;
        }
        if (state == OPEN) {
            state = READING;
            notifyAll();
        }
        try {
            source.close();
        } catch (RuntimeException e) {
            state = FAILED;
            notifyAll();
            throw e;
        }
        if (state == COMPLETING) {
            complete(new Result(buffer, count, phase));
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(buffer, count, phase, state);
    }

    private int sum(byte[] array) {
        int result = 0;
        for (byte b : array) {
            result += b & 0xFF;
        }
        return result;
    }

    private void complete(Result result) {
        state = CLOSED;
        notifyAll();
        try {
            listener.complete(result);
        } catch (RuntimeException e) {
            state = FAILED;
            notifyAll();
            throw e;
        } finally {
            listener = null;
        }
    }

    private byte[] releaseChunk(byte[] chunk) {
        byte[] result = chunkToRelease;
        chunkToRelease = null;
        return result;
    }

    static class ChunkSource {
        public abstract byte[] read();
        public abstract void close();
    }

    static class CompletionListener {
        public abstract void complete(Result result);
    }

    static class Result {
        public final byte[] data;
        public final int count;
        public final int phase;
        public final Throwable error;

        public Result(byte[] data, int count, int phase) {
            this(data, count, phase, null);
        }

        public Result(byte[] data, int count, int phase, Throwable error) {
            this.data = data;
            this.count = count;
            this.phase = phase;
            this.error = error;
        }
    }

    static class Snapshot {
        public final byte[] buffer;
        public final int count;
        public final int phase;
        public final int state;

        public Snapshot(byte[] buffer, int count, int phase, int state) {
            this.buffer = buffer;
            this.count = count;
            this.phase = phase;
            this.state = state;
        }
    }
}
