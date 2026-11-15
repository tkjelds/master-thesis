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
        public final long readCursor;
        public final long heat;
        public final long threshold;
        public final Mode mode;

        private Snapshot(byte[] storage, long readCursor, long heat,
                         long threshold, Mode mode) {
            this.storage = storage;
            this.readCursor = readCursor;
            this.heat = heat;
            this.threshold = threshold;
            this.mode = mode;
        }

        public byte[] getStorage() {
            return copyBytes(storage);
        }

        public long getReadCursor() {
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

    private final Object stateMonitor = new Object();
    private byte[] storage = new byte[0];
    private int storageLength;
    private int readCursor;
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
        byte[] payload = copyBytes(data);

        synchronized (stateMonitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("stream is closed");
                }
                if ((long) payload.length > threshold) {
                    throw new IllegalArgumentException(
                            "payload exceeds the heat threshold");
                }
                if (mode == Mode.NOMINAL
                        && (long) payload.length <= threshold - heat) {
                    break;
                }
                stateMonitor.wait();
            }

            long requiredLength = (long) storageLength + payload.length;
            if (requiredLength > Integer.MAX_VALUE) {
                throw new OutOfMemoryError("stream storage exceeds array limits");
            }
            ensureCapacity((int) requiredLength);
            copyInto(payload, storage, storageLength);
            heat += payload.length;
            storageLength += payload.length;
            updateMode();
            stateMonitor.notifyAll();
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (stateMonitor) {
            while (true) {
                int unread = storageLength - readCursor;
                if (unread > 0 && (mode == Mode.NOMINAL || mode == Mode.CLOSED)) {
                    int count = unread < maxBytes ? unread : maxBytes;
                    byte[] result = new byte[count];
                    copyRange(storage, readCursor, result, 0, count);
                    readCursor += count;
                    return result;
                }
                if (mode == Mode.CLOSED) {
                    return null;
                }
                stateMonitor.wait();
            }
        }
    }

    public void cool(long amount) {
        synchronized (stateMonitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException(
                        "cooling amount must not be negative");
            }
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            stateMonitor.notifyAll();
        }
    }

    public void setThreshold(long newThreshold) {
        synchronized (stateMonitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("stream is closed");
            }
            if (newThreshold <= 0) {
                throw new IllegalArgumentException("threshold must be positive");
            }
            threshold = newThreshold;
            updateMode();
            stateMonitor.notifyAll();
        }
    }

    public void close() {
        synchronized (stateMonitor) {
            if (mode != Mode.CLOSED) {
                mode = Mode.CLOSED;
                stateMonitor.notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            byte[] copy = new byte[storageLength];
            copyRange(storage, 0, copy, 0, storageLength);
            return new Snapshot(copy, readCursor, heat, threshold, mode);
        }
    }

    private void updateMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    private void ensureCapacity(int required) {
        if (required <= storage.length) {
            return;
        }
        int capacity = storage.length == 0 ? 16 : storage.length;
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = required;
                break;
            }
            capacity *= 2;
        }
        byte[] expanded = new byte[capacity];
        copyRange(storage, 0, expanded, 0, storageLength);
        storage = expanded;
    }

    private static byte[] copyBytes(byte[] source) {
        byte[] copy = new byte[source.length];
        copyRange(source, 0, copy, 0, source.length);
        return copy;
    }

    private static void copyInto(byte[] source, byte[] destination, int offset) {
        for (int i = 0; i < source.length; i++) {
            destination[offset + i] = source[i];
        }
    }

    private static void copyRange(byte[] source, int sourceOffset,
                                  byte[] destination, int destinationOffset,
                                  int length) {
        for (int i = 0; i < length; i++) {
            destination[destinationOffset + i] = source[sourceOffset + i];
        }
    }
}
