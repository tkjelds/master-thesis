import java.io.IOException;
import java.nio.ByteBuffer;

@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private final ChunkSource source;
    private final CompletionListener listener;
    private volatile State state = State.OPEN;
    private ByteBuffer buffer;
    private int byteCount;
    private int phase;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
        buffer = ByteBuffer.allocate(1024); // Initial capacity
    }

    public synchronized byte[] readChunk() throws IOException, InterruptedException {
        while (state == State.READING) {
            wait();
        }
        if (state != State.OPEN && state != State.COMPLETING) {
            return null;
        }
        byte[] chunk = source.read();
        if (chunk == null) {
            state = State.COMPLETING;
            complete(new Result(buffer.array(), byteCount, phase, null));
        } else {
            buffer = ByteBuffer.allocate(Math.max(buffer.capacity(), chunk.length * 2));
            buffer.put(chunk);
            byteCount += chunk.length;
            phase = (phase + sumUnsignedBytes(chunk)) % 360;
        }
        notifyAll();
        return chunk;
    }

    public synchronized void close() {
        while (state == State.READING) {
            wait();
        }
        if (state != State.OPEN && state != State.COMPLETING) {
            return;
        }
        if (state == State.OPEN) {
            state = State.COMPLETING;
        }
        ByteBuffer finalBuffer = buffer;
        int finalByteCount = byteCount;
        int finalPhase = phase;
        IOException closeException = null;
        try {
            source.close();
        } catch (IOException e) {
            closeException = e;
        }
        complete(new Result(finalBuffer.array(), finalByteCount, finalPhase, closeException));
    }

    public synchronized void snapshot() {
        while (state == State.READING) {
            wait();
        }
        if (state != State.OPEN && state != State.COMPLETING && state != State.CLOSED) {
            return;
        }
        byte[] snapshotBuffer = buffer.array();
        int snapshotByteCount = byteCount;
        int snapshotPhase = phase;
        State snapshotState = state;
        System.out.println("Snapshot: " + new Result(snapshotBuffer, snapshotByteCount, snapshotPhase, null));
    }

    private int sumUnsignedBytes(byte[] bytes) {
        int sum = 0;
        for (byte b : bytes) {
            sum += b & 0xFF;
        }
        return sum;
    }

    private synchronized void complete(Result result) {
        if (state != State.COMPLETING && state != State.CLOSED && state != State.FAILED) {
            return;
        }
        state = State.CLOSED;
        notifyAll();
        try {
            listener.complete(result);
        } catch (RuntimeException e) {
            state = State.FAILED;
            notifyAll();
            throw e;
        } finally {
            buffer = null;
            source = null;
            listener = null;
        }
    }

    enum State {
        OPEN, READING, COMPLETING, CLOSED, FAILED
    }

    static class Result {
        final byte[] bytes;
        final int byteCount;
        final int phase;
        final IOException exception;

        Result(byte[] bytes, int byteCount, int phase, IOException exception) {
            this.bytes = bytes;
            this.byteCount = byteCount;
            this.phase = phase;
            this.exception = exception;
        }
    }

    static interface ChunkSource {
        byte[] read() throws IOException;
        void close() throws IOException;
    }

    static interface CompletionListener {
        void complete(Result result) throws IOException;
    }
}
