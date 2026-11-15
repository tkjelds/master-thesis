import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

@HarmonicBufferedStream.ThreadSafe
class HarmonicBufferedStream implements ChunkSource, CompletionListener {
    @interface ThreadSafe {}

    private static final int OPEN = 0;
    private static final int READING = 1;
    private static final int COMPLETING = 2;
    private static final int CLOSED = 3;
    private static final int FAILED = 4;

    private byte[] buffer;
    private int count;
    private int phase;
    private int state = OPEN;
    private byte[] currentChunk = null;
    private IOException failure = null;

    @Override
    public byte[] read() throws IOException {
        synchronized (this) {
            while (state == READING || state == COMPLETING) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    handleFailure(new IOException("Interrupted", e));
                }
            }

            if (state == FAILED) {
                throw failure;
            }

            if (state == CLOSED) {
                return null;
            }

            state = READING;
            notifyAll();
        }

        try {
            currentChunk = source.read();
            if (currentChunk != null) {
                count += currentChunk.length;
                phase = (phase + sum(currentChunk)) % 360;
            } else {
                state = COMPLETING;
            }
        } catch (IOException e) {
            handleFailure(e);
        } finally {
            synchronized (this) {
                state = OPEN;
                notifyAll();
            }
        }

        return currentChunk;
    }

    @Override
    public void close() {
        IOException ex = null;

        synchronized (this) {
            if (state == CLOSED || state == FAILED) {
                return;
            }

            state = COMPLETING;
        }

        try {
            source.close();
        } catch (IOException e) {
            ex = new IOException("Failed to close source", e);
        } finally {
            synchronized (this) {
                if (state == COMPLETING) {
                    state = CLOSED;
                    notifyAll();
                }

                if (ex != null) {
                    handleFailure(ex);
                }
            }
        }
    }

    public Chunk snapshot() {
        synchronized (this) {
            while (state == READING || state == COMPLETING) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    handleFailure(new IOException("Interrupted", e));
                }
            }

            if (state == FAILED) {
                throw failure;
            }

            byte[] snapshotBuffer = new byte[count];
            int index = 0;

            if (currentChunk != null) {
                System.arraycopy(currentChunk, 0, snapshotBuffer, index, currentChunk.length);
                index += currentChunk.length;
            }

            return new Chunk(snapshotBuffer, count, phase, failure);
        }
    }

    private void handleFailure(IOException e) {
        if (failure == null || failure.getMessage().compareTo(e.getMessage()) > 0) {
            failure = e;
        }
    }

    private int sum(byte[] array) {
        int result = 0;

        for (byte b : array) {
            result += (int) b & 0xFF;
        }

        return result;
    }

    @Override
    public void complete(Result result) {
        IOException ex = null;

        synchronized (this) {
            if (state == FAILED) {
                return;
            }

            state = COMPLETING;

            if (currentChunk != null) {
                result.attach(currentChunk);
            }

            if (failure != null) {
                ex = failure;
            } else if (currentChunk == null) {
                result.attach(buffer);
            }
        }

        try {
            listener.complete(result);
        } catch (IOException e) {
            handleFailure(e);
        }

        synchronized (this) {
            if (state == COMPLETING) {
                state = CLOSED;
                notifyAll();
            }

            if (ex != null) {
                handleFailure(ex);
            }
        }
    }

    private ChunkSource source;
    private CompletionListener listener;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
    }
}

@Chunk.ThreadSafe
class Chunk {
    @interface ThreadSafe {}

    private byte[] data;
    private int count;
    private int phase;
    private IOException failure;

    public Chunk(byte[] data, int count, int phase, IOException failure) {
        this.data = data;
        this.count = count;
        this.phase = phase;
        this.failure = failure;
    }

    public byte[] getData() {
        return data;
    }

    public int getCount() {
        return count;
    }

    public int getPhase() {
        return phase;
    }

    public IOException getFailure() {
        return failure;
    }

    public void attach(byte[] chunk) {
        byte[] newData = new byte[count + chunk.length];
        System.arraycopy(data, 0, newData, 0, count);
        System.arraycopy(chunk, 0, newData, count, chunk.length);
        data = newData;
        count += chunk.length;
    }
}

interface ChunkSource {
    byte[] read() throws IOException;
    void close() throws IOException;
}

interface CompletionListener {
    void complete(Result result) throws IOException;
}

@Result.ThreadSafe
class Result {
    @interface ThreadSafe {}

    private byte[] data;

    public Result() {}

    public void attach(byte[] chunk) {
        if (data == null) {
            data = chunk;
        } else {
            byte[] newData = new byte[data.length + chunk.length];
            System.arraycopy(data, 0, newData, 0, data.length);
            System.arraycopy(chunk, 0, newData, data.length, chunk.length);
            data = newData;
        }
    }

    public byte[] getData() {
        return data;
    }
}
