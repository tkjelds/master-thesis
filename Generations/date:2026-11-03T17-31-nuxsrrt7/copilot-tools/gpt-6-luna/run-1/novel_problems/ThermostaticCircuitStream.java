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
            return storage.clone();
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
    private int storageLength;
    private int cursor;
    private long heat;
    private long threshold;
    private Mode mode = Mode.NOMINAL;

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
        byte[] payload = data.clone();

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) payload.length > threshold) {
                    throw new IllegalArgumentException("payload exceeds threshold");
                }
                if (mode != Mode.OVERHEATED && (long) payload.length <= threshold - heat) {
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
                int unread = storageLength - cursor;
                if (mode == Mode.CLOSED) {
                    return unread == 0 ? null : consume(maxBytes, unread);
                }
                if (unread > 0 && mode == Mode.NOMINAL) {
                    return consume(maxBytes, unread);
                }
                monitor.wait();
            }
        }
    }

    public void cool(long amount) {
        synchronized (monitor) {
            ensureOpen();
            if (amount < 0) {
                throw new IllegalArgumentException("cooling amount must not be negative");
            }
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        synchronized (monitor) {
            ensureOpen();
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
            mode = Mode.CLOSED;
            monitor.notifyAll();
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            byte[] copy = new byte[storageLength];
            System.arraycopy(storage, 0, copy, 0, storageLength);
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private void append(byte[] payload) {
        if (payload.length > Integer.MAX_VALUE - storageLength) {
            throw new IllegalStateException("stream storage capacity exceeded");
        }
        int requiredLength = storageLength + payload.length;
        if (requiredLength > storage.length) {
            int capacity = storage.length == 0 ? 1 : storage.length;
            while (capacity < requiredLength) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = requiredLength;
                    break;
                }
                capacity *= 2;
            }
            byte[] expanded = new byte[capacity];
            System.arraycopy(storage, 0, expanded, 0, storageLength);
            storage = expanded;
        }
        System.arraycopy(payload, 0, storage, storageLength, payload.length);
        storageLength = requiredLength;
    }

    private byte[] consume(int maxBytes, int unread) {
        int count = Math.min(maxBytes, unread);
        byte[] result = new byte[count];
        System.arraycopy(storage, cursor, result, 0, count);
        cursor += count;
        return result;
    }

    private void updateMode() {
        if (mode != Mode.CLOSED) {
            mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
        }
    }

    private void ensureOpen() {
        if (mode == Mode.CLOSED) {
            throw new IllegalStateException("stream is closed");
        }
    }
}
