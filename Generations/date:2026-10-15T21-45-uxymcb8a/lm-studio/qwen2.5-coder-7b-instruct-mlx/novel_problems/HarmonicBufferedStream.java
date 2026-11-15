import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private static final int OPEN = 0;
    private static final int READING = 1;
    private static final int COMPLETING = 2;
    private static final int CLOSED = 3;
    private static final int FAILED = 4;

    private final ChunkSource source;
    private final CompletionListener listener;
    private byte[] buffer = new byte[0];
    private int count = 0;
    private int phase = 0;
    private boolean closeRequested = false;
    private Throwable error = null;
    private AtomicInteger state = new AtomicInteger(OPEN);

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
    }

    public synchronized byte[] readChunk() throws InterruptedException {
        while (state.get() == READING) {
            wait();
        }
        if (state.get() >= FAILED) {
            throw new IllegalStateException("Stream is in failed state");
        }
        if (state.get() == CLOSED && buffer.length == 0) {
            return null;
        }
        state.set(READING);
        notifyAll();
        try {
            byte[] chunk = source.read();
            if (chunk != null) {
                appendChunk(chunk);
            } else if (closeRequested) {
                complete();
            }
        } finally {
            state.set(OPEN);
            notifyAll();
        }
        return buffer.length == 0 ? null : buffer;
    }

    public synchronized void close() {
        if (state.get() >= COMPLETING) {
            return;
        }
        state.set(CLOSED);
        if (closeRequested) {
            complete();
        } else if (buffer.length == 0 && source != null) {
            new Thread(() -> complete()).start();
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(buffer, count, phase, state.get());
    }

    private synchronized void appendChunk(byte[] chunk) {
        int newLength = count + chunk.length;
        byte[] newBuffer = new byte[newLength];
        System.arraycopy(buffer, 0, newBuffer, 0, count);
        System.arraycopy(chunk, 0, newBuffer, count, chunk.length);
        buffer = newBuffer;
        int newPhase = phase + (int) ((count & 0xFFL) * (chunk[0] & 0xFFL));
        newPhase %= 360;
        phase = newPhase;
        count = newLength;
    }

    private synchronized void complete() {
        if (state.get() >= COMPLETING) {
            return;
        }
        state.set(COMPLETING);
        try (ChunkSource localSource = source) {
            if (buffer.length == 0 && closeRequested) {
                listener.complete(new Result(buffer, count, phase, null));
            } else if (buffer.length == 0) {
                buffer = new byte[0];
                phase = 0;
            } else if (error != null) {
                listener.complete(new Result(buffer, count, phase, error));
            } else {
                listener.complete(new Result(buffer, count, phase, null));
            }
        } catch (IOException e) {
            if (error == null) {
                error = e;
            } else {
                error.addSuppressed(e);
            }
            listener.complete(new Result(buffer, count, phase, error));
        } finally {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException e) {
                    if (error == null) {
                        error = e;
                    } else {
                        error.addSuppressed(e);
                    }
                }
            }
            source = null;
            buffer = null;
            state.set(CLOSED);
        }
    }

    public static class Snapshot {
        private final byte[] buffer;
        private final int count;
        private final int phase;
        private final int state;

        public Snapshot(byte[] buffer, int count, int phase, int state) {
            this.buffer = buffer.clone();
            this.count = count;
            this.phase = phase;
            this.state = state;
        }

        public byte[] getBuffer() {
            return buffer.clone();
        }

        public int getCount() {
            return count;
        }

        public int getPhase() {
            return phase;
        }

        public int getState() {
            return state;
        }
    }

    public static class Result {
        private final byte[] buffer;
        private final int count;
        private final int phase;
        private final Throwable error;

        public Result(byte[] buffer, int count, int phase, Throwable error) {
            this.buffer = buffer.clone();
            this.count = count;
            this.phase = phase;
            this.error = error;
        }

        public byte[] getBuffer() {
            return buffer.clone();
        }

        public int getCount() {
            return count;
        }

        public int getPhase() {
            return phase;
        }

        public Throwable getError() {
            return error;
        }
    }

    @FunctionalInterface
    public interface ChunkSource {
        byte[] read() throws IOException;
        void close() throws IOException;
    }

    @FunctionalInterface
    public interface CompletionListener {
        void complete(Result result);
    }
}
