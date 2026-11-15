@HarmonicBufferedStream.ThreadSafe
public class HarmonicBufferedStream {
    @interface ThreadSafe {}

    private static final int MAX_PHASE = 360;
    private volatile State state = State.OPEN;
    private final ChunkSource source;
    private final CompletionListener listener;

    // Buffer state
    private byte[] buffer = new byte[0];
    private int bufferCount = 0;
    private volatile int phase = 0;

    // Synchronization control
    private boolean hasReadRequest = false;
    private boolean hasCloseRequest = false;

    // Completion state
    private volatile Throwable completionError;
    private byte[] retainedChunk = null;

    public HarmonicBufferedStream(ChunkSource source, CompletionListener listener) {
        this.source = source;
        this.listener = listener;
    }

    public byte[] readChunk() throws RuntimeException {
        synchronized (this) {
            if (state == State.COMPLETING || state == State.FAILED ||
                state == State.CLOSED) {
                return null;
            }

            if (hasCloseRequest && state == State.OPEN) {
                state = State.CLOSED;
            }

            if (state == State.OPEN && bufferCount > 0) {
                return copyChunk();
            }

            if (state == State.OPEN && !hasReadRequest) {
                hasReadRequest = true;
            } else if (state == State.OPEN && hasReadRequest) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Read interrupted", e);
                }
            }

            if (state == State.COMPLETING || state == State.FAILED ||
                state == State.CLOSED) {
                return null;
            }

            if (bufferCount > 0) {
                return copyChunk();
            }
        }

        // Perform the actual read outside the lock
        byte[] chunk = source.read();
        if (chunk == null) {
            return null;
        }

        synchronized (this) {
            if (state != State.OPEN || hasCloseRequest) {
                return null;
            }

            // Copy chunk before retaining it
            byte[] copy = new byte[chunk.length];
            System.arraycopy(chunk, 0, copy, 0, chunk.length);

            // Update buffer state
            byte[] newBuffer = new byte[bufferCount + copy.length];
            System.arraycopy(buffer, 0, newBuffer, 0, bufferCount);
            System.arraycopy(copy, 0, newBuffer, bufferCount, copy.length);

            // Update phase
            int newPhase = phase;
            for (byte b : copy) {
                newPhase += Math.abs(b);
                if (newPhase >= MAX_PHASE) {
                    newPhase -= MAX_PHASE;
                }
            }

            buffer = newBuffer;
            bufferCount += copy.length;
            phase = newPhase;

            // Notify waiting threads
            notifyAll();
        }

        return copy;
    }

    public void close() {
        synchronized (this) {
            if (state == State.COMPLETING || state == State.FAILED ||
                state == State.CLOSED) {
                return;
            }

            if (state == State.OPEN && !hasReadRequest) {
                hasCloseRequest = true;
                state = State.CLOSED;

                // Perform completion immediately
                complete();
            } else {
                hasCloseRequest = true;
            }
        }
    }

    public Object snapshot() {
        synchronized (this) {
            byte[] copy = new byte[bufferCount];
            System.arraycopy(buffer, 0, copy, 0, bufferCount);
            return new Object() {
                public byte[] getBuffer() { return copy; }
                public int getCount() { return bufferCount; }
                public int getPhase() { return phase; }
                public State getState() { return state; }
            };
        }
    }

    private void complete() {
        synchronized (this) {
            if (state == State.COMPLETING || state == State.FAILED ||
                state == State.CLOSED) {
                return;
            }

            state = State.COMPLETING;

            // Perform source close outside the lock
            try {
                if (source != null) {
                    source.close();
                }
            } catch (Throwable t) {
                completionError = t;
            }

            // Perform listener callback outside the lock
            try {
                if (listener != null) {
                    Result result = new Result(bufferCount, phase);
                    listener.complete(result);
                }
            } catch (Throwable t) {
                if (completionError == null) {
                    completionError = t;
                } else {
                    t.addSuppressed(completionError);
                }
            }

            // Update state and notify
            if (completionError != null) {
                state = State.FAILED;
            } else {
                state = State.CLOSED;
            }

            notifyAll();
        }
    }

    private byte[] copyChunk() {
        byte[] chunk = new byte[bufferCount];
        System.arraycopy(buffer, 0, chunk, 0, bufferCount);
        return chunk;
    }

    private static class Result {
        final int count;
        final int phase;
        final Throwable error;

        public Result(int count, int phase) {
            this.count = count;
            this.phase = phase;
            this.error = null;
        }

        public Result(int count, int phase, Throwable error) {
            this.count = count;
            this.phase = phase;
            this.error = error;
        }
    }

    private interface ChunkSource {
        byte[] read();
        void close();
    }

    private interface CompletionListener {
        void complete(Result result);
    }

    private enum State {
        OPEN, READING, COMPLETING, CLOSED, FAILED
    }
}
