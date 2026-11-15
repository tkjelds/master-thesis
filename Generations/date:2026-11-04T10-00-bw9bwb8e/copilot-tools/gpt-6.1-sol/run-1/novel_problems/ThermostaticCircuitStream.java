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

        byte[] payload = copy(data);

        synchronized (monitor) {
            while (true) {
                requireOpen();

                if (payload.length > threshold) {
                    throw new IllegalArgumentException(
                            "Payload exceeds the current threshold");
                }

                // Subtraction avoids overflowing heat + payload.length.
                if (mode == Mode.NOMINAL
                        && payload.length <= threshold - heat) {
                    break;
                }

                monitor.wait();
            }

            if (payload.length > 2147483647 - storage.length) {
                throw new OutOfMemoryError("Storage exceeds array capacity");
            }

            byte[] appended = new byte[storage.length + payload.length];
            for (int i = 0; i < storage.length; i++) {
                appended[i] = storage[i];
            }
            for (int i = 0; i < payload.length; i++) {
                appended[storage.length + i] = payload[i];
            }

            storage = appended;
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
                    && (cursor == storage.length || mode != Mode.NOMINAL)) {
                monitor.wait();
            }

            if (cursor == storage.length) {
                return null;
            }

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
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Cooling amount must be nonnegative");
        }

        synchronized (monitor) {
            requireOpen();
            heat = amount >= heat ? 0 : heat - amount;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive");
        }

        synchronized (monitor) {
            requireOpen();
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
            return new Snapshot(copy(storage), cursor, heat, threshold, mode);
        }
    }

    private void requireOpen() {
        if (mode == Mode.CLOSED) {
            throw new IllegalStateException("Stream is closed");
        }
    }

    private void recomputeMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    private static byte[] copy(byte[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
