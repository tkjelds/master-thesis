@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private static final int OPEN = 0, READING = 1, COMPLETING = 2, CLOSED = 3, FAILED = 4;
    private int state = OPEN;
    private ChunkSource source;
    private CompletionListener listener;
    private byte[] buffer;
    private int count, phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws InterruptedException {
        synchronized (this) {
            while (state == OPEN || state == READING) {
                wait();
            }
        }

        if (state == COMPLETING || state == CLOSED) {
            return null;
        } else {
            byte[] chunk = source.read();
            if (chunk != null) {
                count += chunk.length;
                phase = (phase + sum(chunk)) % 360;
                buffer = concat(buffer, chunk);
            }
            return chunk;
        }
    }

    public void close() {
        synchronized (this) {
            if (state == OPEN || state == READING) {
                state = COMPLETING;
            }
        }

        if (state != CLOSED && state != FAILED) {
            Thread completionThread = new Thread(() -> complete());
            completionThread.start();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            while (state == OPEN || state == READING) {
                try { wait(); } catch (InterruptedException e) {}
            }
        }

        return new Snapshot(state, buffer, count, phase);
    }

    private void complete() {
        try {
            synchronized (this) {
                if (state == COMPLETING) {
                    state = CLOSED;
                    source.close();
                    listener.complete(new Result(buffer, count, phase));
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                state = FAILED;
                listener.complete(new Result(null, 0, phase));
            }
        } finally {
            source = null;
            listener = null;
        }
    }

    private int sum(byte[] bytes) {
        int sum = 0;
        for (byte b : bytes) {
            sum += b & 0xFF;
        }
        return sum;
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    public static class Snapshot {
        private int state;
        private byte[] buffer;
        private int count, phase;

        public Snapshot(int state, byte[] buffer, int count, int phase) {
            this.state = state;
            this.buffer = buffer;
            this.count = count;
            this.phase = phase;
        }

        // Getters for state, buffer, count, and phase
    }

    public static class Result {
        private byte[] bytes;
        private int count, phase;

        public Result(byte[] bytes, int count, int phase) {
            this.bytes = bytes;
            this.count = count;
            this.phase = phase;
        }

        // Getter for bytes
    }
}

interface ChunkSource {
    byte[] read();
    void close();
}

interface CompletionListener {
    void complete(HarmonicBufferedStream.Result result);
}
