@ThermostaticCircuitStream.ThreadSafe
public final class ThermostaticCircuitStream {
    @interface ThreadSafe {}


    public enum Mode {
        NOMINAL,
        OVERHEATED
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
            byte[] copy = new byte[storage.length];
            System.arraycopy(storage, 0, copy, 0, storage.length);
            return copy;
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

    private static final int MAX_STORAGE = Integer.MAX_VALUE - 8;

    private final Object lock = new Object();
    private byte[] storage = new byte[16];
    private int size;
    private int readCursor;
    private long heat;
    private long threshold;
    private Mode mode;
    private boolean closed;

    public ThermostaticCircuitStream(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        synchronized (lock) {
            this.threshold = threshold;
            this.heat = 0;
            this.size = 0;
            this.readCursor = 0;
            this.closed = false;
            recomputeMode();
        }
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must be non-null and non-empty");
        }
        final byte[] payload = data.clone();
        final int len = payload.length;

        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("stream is closed");
                }
                if (len > threshold) {
                    throw new IllegalArgumentException("payload exceeds current threshold");
                }
                if (heat < threshold && len <= threshold - heat) {
                    break;
                }
                lock.wait();
            }

            if (len > MAX_STORAGE - size) {
                throw new IllegalStateException("storage capacity exhausted");
            }
            ensureCapacity(size + len);
            System.arraycopy(payload, 0, storage, size, len);
            size += len;
            heat += len;
            recomputeMode();
            lock.notifyAll();
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        synchronized (lock) {
            while (true) {
                int unread = size - readCursor;
                if (unread > 0 && (closed || mode == Mode.NOMINAL)) {
                    int n = Math.min(maxBytes, unread);
                    byte[] out = new byte[n];
                    System.arraycopy(storage, readCursor, out, 0, n);
                    readCursor += n;
                    return out;
                }
                if (closed) {
                    return null;
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
            heat = heat > amount ? heat - amount : 0L;
            recomputeMode();
            lock.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("stream is closed");
            }
            this.threshold = threshold;
            recomputeMode();
            lock.notifyAll();
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
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

    // Caller must hold lock.
    private void recomputeMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    // Caller must hold lock.
    private void ensureCapacity(int needed) {
        if (needed <= storage.length) {
            return;
        }
        long cap = Math.max(1, storage.length);
        while (cap < needed) {
            cap = cap * 2;
        }
        if (cap > MAX_STORAGE) {
            cap = MAX_STORAGE;
        }
        byte[] grown = new byte[(int) cap];
        System.arraycopy(storage, 0, grown, 0, size);
        storage = grown;
    }
}
