@ThermostaticCircuitStream.ThreadSafe
public final class ThermostaticCircuitStream {
    @interface ThreadSafe {}

    public enum Mode {
        NOMINAL,
        OVERHEATED,
        CLOSED
    }

    public static final class Snapshot {
        private final byte[] storage;
        private final int cursor;
        private final long heat;
        private final long threshold;
        private final Mode mode;

        private Snapshot(byte[] storage, int cursor, long heat,
                         long threshold, Mode mode) {
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
            throw new IllegalArgumentException("Data must be nonempty");
        }
        byte[] copy = data.clone();

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("Stream is closed");
                }
                if ((long) copy.length > threshold) {
                    throw new IllegalArgumentException(
                            "Payload exceeds the current threshold");
                }
                if (mode == Mode.NOMINAL
                        && heat <= threshold - copy.length) {
                    break;
                }
                monitor.wait();
            }

            long required = (long) size + copy.length;
            if (required > Integer.MAX_VALUE) {
                throw new IllegalStateException("Storage capacity exceeded");
            }
            ensureCapacity((int) required);
            System.arraycopy(copy, 0, storage, size, copy.length);
            size = (int) required;
            heat += copy.length;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public byte[] readWhenReady(int maxBytes) throws InterruptedException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }

        synchronized (monitor) {
            while (true) {
                if (cursor < size
                        && (mode == Mode.NOMINAL || mode == Mode.CLOSED)) {
                    int count = Math.min(maxBytes, size - cursor);
                    byte[] result = new byte[count];
                    System.arraycopy(storage, cursor, result, 0, count);
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
        if (amount < 0) {
            throw new IllegalArgumentException("Cooling amount must not be negative");
        }

        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
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
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
            this.threshold = threshold;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public void close() {
        synchronized (monitor) {
            if (mode != Mode.CLOSED) {
                mode = Mode.CLOSED;
                monitor.notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            byte[] copy = new byte[size];
            System.arraycopy(storage, 0, copy, 0, size);
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private void recomputeMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    private void ensureCapacity(int required) {
        if (required <= storage.length) {
            return;
        }

        long grown = Math.max((long) required, (long) storage.length * 2);
        int capacity = (int) Math.min(grown, (long) Integer.MAX_VALUE);
        byte[] replacement = new byte[capacity];
        System.arraycopy(storage, 0, replacement, 0, size);
        storage = replacement;
    }
}
