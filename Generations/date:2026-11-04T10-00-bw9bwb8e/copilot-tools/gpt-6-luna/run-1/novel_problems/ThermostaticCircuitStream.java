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
            return copyOf(storage);
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
    private byte[] buffer = new byte[0];
    private int length;
    private int cursor;
    private long heat;
    private long threshold;
    private Mode mode = Mode.NOMINAL;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive");
        }
        threshold = heatThreshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data must not be null or empty");
        }
        byte[] payload = copyOf(data);

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("Stream is closed");
                }
                if (payload.length > threshold) {
                    throw new IllegalArgumentException("Payload exceeds threshold");
                }
                if (mode == Mode.NOMINAL && (long) payload.length <= threshold - heat) {
                    append(payload);
                    heat += payload.length;
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
                int unread = length - cursor;
                if (mode == Mode.CLOSED) {
                    return unread == 0 ? null : consume(Math.min(maxBytes, unread));
                }
                if (unread > 0 && mode == Mode.NOMINAL) {
                    return consume(Math.min(maxBytes, unread));
                }
                monitor.wait();
            }
        }
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Cooling amount must not be negative");
        }

        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        if (newThreshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive");
        }

        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
            threshold = newThreshold;
            updateMode();
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
            byte[] storage = new byte[length];
            for (int i = 0; i < length; i++) {
                storage[i] = buffer[i];
            }
            return new Snapshot(storage, cursor, heat, threshold, mode);
        }
    }

    private void updateMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    private void append(byte[] data) {
        long required = (long) length + data.length;
        if (required > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Stream storage is too large");
        }

        if (required > buffer.length) {
            int capacity = buffer.length == 0 ? 16 : buffer.length;
            while (capacity < required) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = Integer.MAX_VALUE;
                    break;
                }
                capacity *= 2;
            }

            byte[] replacement = new byte[capacity];
            for (int i = 0; i < length; i++) {
                replacement[i] = buffer[i];
            }
            buffer = replacement;
        }

        for (int i = 0; i < data.length; i++) {
            buffer[length + i] = data[i];
        }
        length += data.length;
    }

    private byte[] consume(int count) {
        byte[] result = new byte[count];
        for (int i = 0; i < count; i++) {
            result[i] = buffer[cursor + i];
        }
        cursor += count;
        return result;
    }

    private static byte[] copyOf(byte[] source) {
        byte[] copy = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
