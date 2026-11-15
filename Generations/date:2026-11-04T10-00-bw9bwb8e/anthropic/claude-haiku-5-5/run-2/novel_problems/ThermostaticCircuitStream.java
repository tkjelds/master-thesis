@ThermostaticCircuitStream.ThreadSafe
public final class ThermostaticCircuitStream {
    @interface ThreadSafe {}


    public enum Mode {
        NOMINAL,
        OVERHEATED,
        CLOSED
    }

    public static final class Snapshot {
        private final byte[] storage;
        private final int readCursor;
        private final long heat;
        private final long threshold;
        private final Mode mode;

        private Snapshot(byte[] storage, int readCursor, long heat, long threshold, Mode mode) {
            this.storage = storage;
            this.readCursor = readCursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getStorage() {
            return storage.clone();
        }

        public int getReadCursor() {
            return readCursor;
        }

        public long getHeat() {
            return heat;
        }

        public long getThreshold() {
            return threshold;
        }

        public Mode getMode() {
            return mode;
        }
    }

    private final Object lock = new Object();

    // Guarded by lock
    private byte[] storage = new byte[16];
    private int size;
    private int readCursor;
    private long heat;
    private long threshold;
    private Mode mode;
    private boolean closed;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("heat threshold must be positive");
        }
        this.threshold = heatThreshold;
        this.heat = 0L;
        this.readCursor = 0;
        this.size = 0;
        this.closed = false;
        this.mode = Mode.NOMINAL;
    }

    /**
     * Appends the payload when the stream is not overheated and the entire payload
     * fits under the threshold. Blocks (interruptibly) otherwise.
     * Policy: payloads larger than the current threshold are rejected immediately
     * or while waiting if the threshold is lowered below their size.
     */
    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must be non-null and non-empty");
        }
        final byte[] payload = data.clone();
        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("stream is closed");
                }
                if (payload.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds current heat threshold");
                }
                if (mode == Mode.NOMINAL && payload.length <= threshold - heat) {
                    appendLocked(payload);
                    heat += payload.length;
                    refreshModeLocked();
                    lock.notifyAll();
                    return;
                }
                lock.wait();
            }
        }
    }

    /**
     * Consumes up to maxBytes unread bytes through the shared cursor when the mode is
     * NOMINAL. After close, drains remaining bytes regardless of heat, then returns null.
     */
    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        synchronized (lock) {
            while (true) {
                int available = size - readCursor;
                if (closed) {
                    if (available == 0) {
                        return null;
                    }
                    return takeLocked(maxBytes);
                }
                if (available > 0 && mode == Mode.NOMINAL) {
                    return takeLocked(maxBytes);
                }
                lock.wait();
            }
        }
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("cooling amount must not be negative");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("stream is closed");
            }
            heat = (amount >= heat) ? 0L : heat - amount;
            refreshModeLocked();
            lock.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        if (newThreshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("stream is closed");
            }
            threshold = newThreshold;
            refreshModeLocked();
            lock.notifyAll();
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            refreshModeLocked();
            lock.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            byte[] copy = new byte[size];
            System.arraycopy(storage, 0, copy, 0, size);
            return new Snapshot(copy, readCursor, heat, threshold, mode);
        }
    }

    // Must be called while holding lock.
    private void refreshModeLocked() {
        if (closed) {
            mode = Mode.CLOSED;
        } else if (heat >= threshold) {
            mode = Mode.OVERHEATED;
        } else {
            mode = Mode.NOMINAL;
        }
    }

    // Must be called while holding lock.
    private void appendLocked(byte[] src) {
        int len = src.length;
        if (len > Integer.MAX_VALUE - size) {
            throw new IllegalStateException("storage capacity exhausted");
        }
        int needed = size + len;
        if (needed > storage.length) {
            long cap = Math.max((long) needed, 2L * storage.length);
            if (cap > Integer.MAX_VALUE) {
                cap = Integer.MAX_VALUE;
            }
            byte[] grown = new byte[(int) cap];
            System.arraycopy(storage, 0, grown, 0, size);
            storage = grown;
        }
        System.arraycopy(src, 0, storage, size, len);
        size = needed;
    }

    // Must be called while holding lock and with at least one unread byte available.
    private byte[] takeLocked(int maxBytes) {
        int n = Math.min(maxBytes, size - readCursor);
        byte[] out = new byte[n];
        System.arraycopy(storage, readCursor, out, 0, n);
        readCursor += n;
        return out;
    }
}
