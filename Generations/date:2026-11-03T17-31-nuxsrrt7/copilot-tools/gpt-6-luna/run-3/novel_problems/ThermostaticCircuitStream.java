@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
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
    private boolean closed;

    public ThermostaticCircuitStream(long heatThreshold) {
        if (heatThreshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        threshold = heatThreshold;
    }

    public void writeWhenCool(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }

        byte[] payload = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            payload[i] = data[i];
        }

        synchronized (monitor) {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) payload.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
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
                if (cursor < size && (closed || mode == Mode.NOMINAL)) {
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
                if (closed) {
                    return null;
                }
                monitor.wait();
            }
        }
    }

    public void cool(long amount) {
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("cooling amount must not be negative");
            }
            if (amount >= heat) {
                heat = 0;
            } else {
                heat -= amount;
            }
            updateMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("stream is closed");
            }
            if (newThreshold <= 0) {
                throw new IllegalArgumentException("threshold must be positive");
            }
            threshold = newThreshold;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void close() {
        synchronized (monitor) {
            if (!closed) {
                closed = true;
                mode = Mode.CLOSED;
                monitor.notifyAll();
            }
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
        long required = (long) size + payload.length;
        if (required > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("stream storage is too large");
        }

        int newSize = (int) required;
        if (newSize > storage.length) {
            long capacity = storage.length == 0 ? 16L : (long) storage.length * 2;
            if (capacity < newSize) {
                capacity = newSize;
            }
            if (capacity > Integer.MAX_VALUE) {
                capacity = Integer.MAX_VALUE;
            }
            byte[] expanded = new byte[(int) capacity];
            for (int i = 0; i < size; i++) {
                expanded[i] = storage[i];
            }
            for (int i = 0; i < payload.length; i++) {
                expanded[size + i] = payload[i];
            }
            storage = expanded;
        } else {
            for (int i = 0; i < payload.length; i++) {
                storage[size + i] = payload[i];
            }
        }
        size = newSize;
    }

    private void updateMode() {
        if (!closed) {
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
        }
    }
}
