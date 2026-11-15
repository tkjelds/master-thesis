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
    }

    private final Object monitor = new Object();
    private byte[] storage = new byte[0];
    private int size;
    private int cursor;
    private long heat;
    private long threshold;
    private Mode mode = Mode.NOMINAL;
    private boolean closed;

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

        byte[] copy = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            copy[i] = data[i];
        }

        synchronized (monitor) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("Stream is closed");
                }
                if (copy.length > threshold) {
                    throw new IllegalArgumentException("Payload exceeds the heat threshold");
                }
                if (mode == Mode.NOMINAL && copy.length <= threshold - heat) {
                    ensureCapacity(size + copy.length);
                    for (int i = 0; i < copy.length; i++) {
                        storage[size + i] = copy[i];
                    }
                    size += copy.length;
                    heat += copy.length;
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
                if (closed) {
                    if (cursor == size) {
                        return null;
                    }
                    return consume(maxBytes);
                }
                if (cursor < size && mode == Mode.NOMINAL) {
                    return consume(maxBytes);
                }
                monitor.wait();
            }
        }
    }

    public void cool(long amount) {
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("Cooling amount must not be negative");
            }
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("Stream is closed");
            }
            if (newThreshold <= 0) {
                throw new IllegalArgumentException("Threshold must be positive");
            }
            threshold = newThreshold;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void close() {
        synchronized (monitor) {
            closed = true;
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

    private byte[] consume(int maxBytes) {
        int count = size - cursor;
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

    private void updateMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    private void ensureCapacity(int required) {
        if (required < 0) {
            throw new IllegalStateException("Storage capacity exceeded");
        }
        if (required <= storage.length) {
            return;
        }

        int capacity = storage.length == 0 ? 16 : storage.length;
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = Integer.MAX_VALUE;
                break;
            }
            capacity *= 2;
        }

        byte[] expanded = new byte[capacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = storage[i];
        }
        storage = expanded;
    }
}
