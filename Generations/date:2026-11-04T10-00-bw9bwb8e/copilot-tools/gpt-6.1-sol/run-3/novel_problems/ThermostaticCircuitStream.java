@ThermostaticCircuitStream.ThreadSafe
public final class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    public enum Mode {
        NOMINAL, OVERHEATED, CLOSED
    }

    public static final class Snapshot {
        public final byte[] storage;
        public final int cursor;
        public final long heat;
        public final long threshold;
        public final Mode mode;

        private Snapshot(byte[] storage, int cursor, long heat,
                         long threshold, Mode mode) {
            this.storage = storage;
            this.cursor = cursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
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
            throw new IllegalArgumentException("Threshold must be positive");
        }
        this.threshold = threshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data must be nonnull and nonempty");
        }

        byte[] payload = new byte[data.length];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = data[i];
        }

        synchronized (monitor) {
            while (true) {
                requireOpen();

                if (payload.length > threshold) {
                    throw new IllegalArgumentException(
                            "Payload exceeds the current threshold");
                }

                // Subtraction is safe only while heat is below threshold.
                if (mode == Mode.NOMINAL
                        && payload.length <= threshold - heat) {
                    break;
                }

                monitor.wait();
            }

            if (payload.length > 2147483647 - size) {
                throw new OutOfMemoryError("Storage exceeds Java array capacity");
            }

            int required = size + payload.length;
            if (required > storage.length) {
                long grown = (long) storage.length * 2;
                if (grown < required) {
                    grown = required;
                }
                if (grown > 2147483647L) {
                    grown = 2147483647L;
                }

                byte[] expanded = new byte[(int) grown];
                for (int i = 0; i < size; i++) {
                    expanded[i] = storage[i];
                }
                storage = expanded;
            }

            for (int i = 0; i < payload.length; i++) {
                storage[size + i] = payload[i];
            }

            size = required;
            heat += payload.length;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (monitor) {
            while (mode != Mode.CLOSED
                    && (cursor == size || mode != Mode.NOMINAL)) {
                monitor.wait();
            }

            if (cursor == size) {
                return null;
            }

            int available = size - cursor;
            int count = available < maxBytes ? available : maxBytes;
            byte[] result = new byte[count];

            for (int i = 0; i < count; i++) {
                result[i] = storage[cursor + i];
            }

            cursor += count;
            return result;
        }
    }

    public void cool(long amount) {
        synchronized (monitor) {
            requireOpen();
            if (amount < 0) {
                throw new IllegalArgumentException(
                        "Cooling amount must be nonnegative");
            }

            heat = amount >= heat ? 0 : heat - amount;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        synchronized (monitor) {
            requireOpen();
            if (threshold <= 0) {
                throw new IllegalArgumentException("Threshold must be positive");
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

    // Called only while holding monitor.
    private void requireOpen() {
        if (mode == Mode.CLOSED) {
            throw new IllegalStateException("Stream is closed");
        }
    }

    // Called only while holding monitor and while open.
    private void recomputeMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }
}
