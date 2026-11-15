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
            byte[] copy = new byte[storage.length];
            for (int i = 0; i < storage.length; i++) {
                copy[i] = storage[i];
            }
            return copy;
        }

        public int getCursor() {
            return cursor;
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

    private final Object monitor = new Object();
    private byte[] storage = new byte[0];
    private int size;
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

        byte[] payload = data.clone();

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) payload.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }
                if (heat < threshold && (long) payload.length <= threshold - heat) {
                    if (payload.length > Integer.MAX_VALUE - size) {
                        throw new IllegalStateException("stream storage capacity exceeded");
                    }
                    append(payload);
                    heat += payload.length;
                    recomputeMode();
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
                int available = size - cursor;
                if (available > 0 && (mode == Mode.NOMINAL || mode == Mode.CLOSED)) {
                    int count = Math.min(maxBytes, available);
                    byte[] result = new byte[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = storage[cursor + i];
                    }
                    cursor += count;
                    return result;
                }
                if (mode == Mode.CLOSED) {
                    return null;
                }
                monitor.wait();
            }
        }
    }

    public void cool(long amount) {
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("cooling amount must not be negative");
            }
            heat = amount >= heat ? 0 : heat - amount;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (threshold <= 0) {
                throw new IllegalArgumentException("threshold must be positive");
            }
            this.threshold = threshold;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public void close() {
        synchronized (monitor) {
            mode = Mode.CLOSED;
            monitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            byte[] copy = new byte[size];
            for (int i = 0; i < size; i++) {
                copy[i] = storage[i];
            }
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private void append(byte[] payload) {
        int required = size + payload.length;
        if (required > storage.length) {
            int capacity = storage.length == 0 ? 16 : storage.length;
            while (capacity < required) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = Integer.MAX_VALUE;
                } else {
                    capacity *= 2;
                }
            }

            byte[] expanded = new byte[capacity];
            for (int i = 0; i < size; i++) {
                expanded[i] = storage[i];
            }
            storage = expanded;
        }

        for (int i = 0; i < payload.length; i++) {
            storage[size + i] = payload[i];
        }
        size = required;
    }

    private void recomputeMode() {
        if (mode != Mode.CLOSED) {
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
        }
    }
}
