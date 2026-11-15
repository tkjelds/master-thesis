@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
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

    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private final Object monitor = new Object();

    // Guarded by monitor
    private byte[] storage = new byte[16];
    private int storedLength = 0;
    private int readCursor = 0;
    private long heat = 0L;
    private long threshold;
    private boolean closed = false;
    private Mode mode = Mode.NOMINAL;

    public ThermostaticCircuitStream(long threshold) {
        if (threshold <= 0L) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.threshold = threshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must be non-null and non-empty");
        }
        // Copy inputs before waiting so caller mutations cannot affect the write.
        final byte[] copy = data.clone();
        final int len = copy.length;

        synchronized (monitor) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) len > threshold) {
                    throw new IllegalArgumentException("payload exceeds current threshold");
                }
                // Overflow-safe check: len <= threshold - heat (heat <= threshold may not hold
                // if threshold was lowered, so compute with signed difference which is safe here
                // because heat >= 0 and threshold > 0).
                if (mode == Mode.NOMINAL && (long) len <= threshold - heat) {
                    ensureCapacity(len);
                    System.arraycopy(copy, 0, storage, storedLength, len);
                    storedLength += len;
                    heat += len;
                    updateMode();
                    monitor.notifyAll();
                    return;
                }
                monitor.wait();
            }
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        synchronized (monitor) {
            while (true) {
                int unread = storedLength - readCursor;
                if (unread > 0 && (closed || mode == Mode.NOMINAL)) {
                    int n = Math.min(maxBytes, unread);
                    byte[] out = new byte[n];
                    System.arraycopy(storage, readCursor, out, 0, n);
                    readCursor += n;
                    return out;
                }
                if (closed && unread == 0) {
                    return null;
                }
                monitor.wait();
            }
        }
    }

    public void cool(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("cooling amount must not be negative");
        }
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("stream is closed");
            }
            heat = (amount >= heat) ? 0L : heat - amount;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        if (threshold <= 0L) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("stream is closed");
            }
            this.threshold = threshold;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void close() {
        synchronized (monitor) {
            closed = true;
            updateMode();
            monitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            byte[] copy = new byte[storedLength];
            System.arraycopy(storage, 0, copy, 0, storedLength);
            return new Snapshot(copy, readCursor, heat, threshold, mode);
        }
    }

    // Must be called while holding monitor.
    private void updateMode() {
        if (closed) {
            mode = Mode.CLOSED;
        } else if (heat >= threshold) {
            mode = Mode.OVERHEATED;
        } else {
            mode = Mode.NOMINAL;
        }
    }

    // Must be called while holding monitor.
    private void ensureCapacity(int extra) {
        long needed = (long) storedLength + (long) extra;
        if (needed <= storage.length) {
            return;
        }
        if (needed > MAX_ARRAY_SIZE) {
            throw new OutOfMemoryError("stream storage limit exceeded");
        }
        long doubled = (long) storage.length * 2L;
        long newCap = Math.max(needed, doubled);
        if (newCap > MAX_ARRAY_SIZE) {
            newCap = MAX_ARRAY_SIZE;
        }
        byte[] grown = new byte[(int) newCap];
        System.arraycopy(storage, 0, grown, 0, storedLength);
        storage = grown;
    }
}
