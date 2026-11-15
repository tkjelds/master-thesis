@ThermostaticCircuitStream.ThreadSafe
public final class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    public enum Mode {
        NOMINAL,
        OVERHEATED,
        CLOSED
    }

    public static final class Snapshot {
        public final byte[] storage;
        public final int cursor;
        public final long heat;
        public final long threshold;
        public final Mode mode;

        private Snapshot(byte[] storage, int cursor, long heat, long threshold, Mode mode) {
            this.storage = storage;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getStorage() {
            return storage.clone();
        }
    }

    private byte[] storage = new byte[0];
    private int cursor;
    private long heat;
    private long threshold;
    private Mode mode = Mode.NOMINAL;

    public ThermostaticCircuitStream(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.threshold = threshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }

        byte[] copy = data.clone();

        synchronized (this) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if (copy.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }

                boolean nominal = heat < threshold;
                boolean fits = nominal && copy.length <= threshold - heat;
                if (fits) {
                    if (copy.length > Integer.MAX_VALUE - storage.length) {
                        throw new IllegalStateException("storage capacity exceeded");
                    }

                    byte[] expanded = new byte[storage.length + copy.length];
                    for (int i = 0; i < storage.length; i++) {
                        expanded[i] = storage[i];
                    }
                    for (int i = 0; i < copy.length; i++) {
                        expanded[storage.length + i] = copy[i];
                    }

                    storage = expanded;
                    heat += copy.length;
                    recomputeMode();
                    notifyAll();
                    return;
                }

                wait();
            }
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (this) {
            while (true) {
                boolean closed = mode == Mode.CLOSED;
                boolean hasUnread = cursor < storage.length;

                if (closed && !hasUnread) {
                    return null;
                }
                if (hasUnread && (closed || mode == Mode.NOMINAL)) {
                    int count = storage.length - cursor;
                    if (count > maxBytes) {
                        count = maxBytes;
                    }

                    byte[] result = new byte[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = storage[cursor + i];
                    }
                    cursor += count;
                    return result;
                }

                wait();
            }
        }
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("cooling amount must not be negative");
        }

        synchronized (this) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }

            heat = amount >= heat ? 0 : heat - amount;
            recomputeMode();
            notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }

        synchronized (this) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }

            this.threshold = threshold;
            recomputeMode();
            notifyAll();
        }
    }

    public void close() {
        synchronized (this) {
            mode = Mode.CLOSED;
            notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(storage.clone(), cursor, heat, threshold, mode);
        }
    }

    private void recomputeMode() {
        if (mode != Mode.CLOSED) {
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
        }
    }
}
