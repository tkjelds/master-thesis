```java
public class HarmonicBufferedStream {
    private final ChunkSource source;
    private final CompletionListener listener;
    private static final int OPEN = 0, READING = 1, COMPLETING = 2, CLOSED = 3, FAILED = 4;
    private volatile int state = OPEN;
    private byte[] buffer;
    private int count, phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
    }

    public synchronized byte[] readChunk() throws InterruptedException {
        while (state == READING || state == COMPLETING) wait();
        if (state != OPEN) return null;
        state = READING;

        byte[] chunk = source.read();
        if (chunk == null) {
            complete(null);
        } else {
            buffer = copy(chunk);
            count += chunk.length;
            for (byte b : chunk) phase = (phase + (b & 0xFF)) % 360;
        }

        state = OPEN;
        notifyAll();
        return buffer;
    }

    private byte[] copy(byte[] data) {
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return copy;
    }

    public synchronized void close() {
        while (state == READING || state == COMPLETING) wait();

        if (state != OPEN && state != CLOSED) return;

        byte[] chunk = null;
        try {
            if (state == OPEN) state = CLOSED;
            chunk = source.read();
        } catch (RuntimeException e) {
            state = FAILED;
            notifyAll();
            throw e;
        } finally {
            source.close();
        }

        complete(chunk);
    }

    private synchronized void complete(byte[] chunk) {
        while (state == READING || state == COMPLETING) wait();

        if (chunk != null) {
            buffer = copy(chunk);
            count += chunk.length;
            for (byte b : chunk) phase = (phase + (b & 0xFF)) % 360;
        }

        state = COMPLETING;
        try {
            listener.complete(new Result(buffer, count, phase));
        } catch (RuntimeException e) {
            state = FAILED;
        } finally {
            buffer = null;
            listener = null;
            result = null;
            state = CLOSED;
            notifyAll();
        }
    }

    public synchronized Snapshot snapshot() {
        while (state == READING || state == COMPLETING) wait();

        return new Snapshot(buffer, count, phase, state);
    }

    private static class Result {
        byte[] bytes;
        int count, phase;

        public Result(byte[] bytes, int count, int phase) {
            this.bytes = bytes;
            this.count = count;
            this.phase = phase;
        }
    }

    public static class Snapshot {
        byte[] bytes;
        int count, phase;
        int state;

        public Snapshot(byte[] bytes, int count, int phase, int state) {
            this.bytes = bytes;
            this.count = count;
            this.phase = phase;
            this.state = state;
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
```
