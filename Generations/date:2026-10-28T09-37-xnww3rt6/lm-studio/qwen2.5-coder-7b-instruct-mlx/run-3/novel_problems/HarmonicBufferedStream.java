```java
import java.io.ByteArrayOutputStream;
import java.io.IOException;

class HarmonicBufferedStream {
    private static final int EOF = -1;
    private static final int OPEN = 0;
    private static final int READING = 1;
    private static final int COMPLETING = 2;
    private static final int CLOSED = 3;
    private static final int FAILED = 4;

    private ChunkSource source;
    private CompletionListener listener;
    private byte[] buffer;
    private int state = OPEN;
    private int readIndex = 0;
    private int writeIndex = 0;
    private int byteCount = 0;
    private int phase = 0;
    private boolean closeRequested;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
    }

    public synchronized byte[] readChunk() throws InterruptedException {
        while (state == READING) {
            wait();
        }
        if (closeRequested && state == CLOSED) {
            return null;
        }

        byte[] chunk = source.read();
        if (chunk == null) {
            close();
        } else {
            int len = chunk.length;
            byte[] newBuffer = new byte[buffer == null ? len : buffer.length + len];
            if (buffer != null) {
                System.arraycopy(buffer, 0, newBuffer, 0, writeIndex);
            }
            System.arraycopy(chunk, 0, newBuffer, writeIndex, len);
            buffer = newBuffer;
            writeIndex += len;
            byteCount += len;
            int chunkPhase = 0;
            for (byte b : chunk) {
                chunkPhase += b & 0xFF;
            }
            phase = (phase + chunkPhase) % 360;
        }
        state = OPEN;
        notifyAll();
        return buffer == null ? null : new byte[0]; // Detach the chunk for external consumption
    }

    public synchronized void close() {
        if (closeRequested || state == CLOSED) {
            return;
        }
        closeRequested = true;
        if (state == OPEN) {
            state = COMPLETING;
            new Thread(() -> complete()).start();
        }
    }

    public synchronized Snapshot snapshot() {
        while (state == READING) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new Snapshot(buffer == null ? 0 : buffer.length, byteCount, phase, state);
    }

    private synchronized void complete() {
        try {
            if (state == COMPLETING) {
                source.close();
                if (listener != null) {
                    listener.complete(new Result(buffer, byteCount, phase, null));
                }
            } else if (state == OPEN) {
                state = CLOSED;
                if (listener != null) {
                    listener.complete(new Result(null, 0, phase, new RuntimeException("Stream closed before completion")));
                }
            }
        } catch (Exception e) {
            state = FAILED;
            if (listener != null) {
                listener.complete(new Result(null, 0, phase, e));
            }
        } finally {
            buffer = null;
            state = CLOSED;
            notifyAll();
            listener = null;
        }
    }

    static class Snapshot {
        final int bufferSize, byteCount, phase;
        final int state;

        public Snapshot(int bufferSize, int byteCount, int phase, int state) {
            this.bufferSize = bufferSize;
            this.byteCount = byteCount;
            this.phase = phase;
            this.state = state;
        }
    }

    static class Result {
        final byte[] bytes;
        final int byteCount, phase;
        final Exception exception;

        public Result(byte[] bytes, int byteCount, int phase, Exception exception) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.exception = exception;
        }
    }

    interface ChunkSource {
        byte[] read() throws IOException, InterruptedException;
        void close() throws IOException;
    }

    interface CompletionListener {
        void complete(Result result) throws RuntimeException, InterruptedException;
    }
}
```
