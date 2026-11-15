@ThermostaticCircuitStream.ThreadSafe
public class ThermostaticCircuitStream {
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
    private int length;
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
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("Data must not be null or empty");
            }

            byte[] copy = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                copy[i] = data[i];
            }

            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("Stream is closed");
                }
                if ((long) copy.length > threshold) {
                    throw new IllegalArgumentException("Payload exceeds threshold");
                }
                if (mode == Mode.NOMINAL && heat <= threshold - copy.length) {
                    break;
                }
                monitor.wait();
            }

            if (copy.length > Integer.MAX_VALUE - length) {
                throw new OutOfMemoryError("Storage exceeds array size limit");
            }
            int newLength = length + copy.length;
            ensureCapacity(newLength);
            for (int i = 0; i < copy.length; i++) {
                storage[length + i] = copy[i];
            }
            length = newLength;
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
            while (mode != Mode.CLOSED
                    && (cursor == length || mode != Mode.NOMINAL)) {
                monitor.wait();
            }

            if (cursor == length) {
                return null;
            }

            int available = length - cursor;
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
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("Cooling amount must not be negative");
            }

            heat = amount >= heat ? 0 : heat - amount;
            recomputeMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                throw new IllegalStateException("Stream is closed");
            }
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
            if (mode != Mode.CLOSED) {
                mode = Mode.CLOSED;
                monitor.notifyAll();
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            byte[] copy = new byte[length];
            for (int i = 0; i < length; i++) {
                copy[i] = storage[i];
            }
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    // Called only while holding monitor.
    private void recomputeMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }

    // Called only while holding monitor.
    private void ensureCapacity(int required) {
        if (required <= storage.length) {
            return;
        }

        long grown = storage.length == 0 ? 16L : (long) storage.length * 2;
        if (grown < required) {
            grown = required;
        }
        if (grown > Integer.MAX_VALUE) {
            grown = Integer.MAX_VALUE;
        }

        byte[] replacement = new byte[(int) grown];
        for (int i = 0; i < length; i++) {
            replacement[i] = storage[i];
        }
        storage = replacement;
    }
}
