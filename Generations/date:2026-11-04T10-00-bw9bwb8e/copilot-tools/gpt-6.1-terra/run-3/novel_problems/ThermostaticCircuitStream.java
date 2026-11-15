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
            throw new IllegalArgumentException("Data must not be null or empty");
        }

        byte[] copy = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            copy[i] = data[i];
        }

        synchronized (monitor) {
            while (true) {
                if (mode == Mode.CLOSED) {
                    throw new IllegalStateException("Stream is closed");
                }
                if ((long) copy.length > threshold) {
                    throw new IllegalArgumentException("Payload exceeds threshold");
                }
                if ((long) size + copy.length > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Storage capacity exceeded");
                }
                if (mode == Mode.NOMINAL
                        && (long) copy.length <= threshold - heat) {
                    break;
                }
                monitor.wait();
            }

            int required = size + copy.length;
            if (required > storage.length) {
                long grown = (long) storage.length + storage.length / 2L + 1L;
                if (grown < required) {
                    grown = required;
                }
                if (grown > Integer.MAX_VALUE) {
                    grown = Integer.MAX_VALUE;
                }
                byte[] expanded = new byte[(int) grown];
                for (int i = 0; i < size; i++) {
                    expanded[i] = storage[i];
                }
                storage = expanded;
            }

            for (int i = 0; i < copy.length; i++) {
                storage[size + i] = copy[i];
            }
            size = required;
            heat += copy.length;
            updateMode();
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
    }

    public void cool(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Cooling amount must not be negative");
        }

        synchronized (monitor) {
            ensureOpen();
            heat = amount >= heat ? 0 : heat - amount;
            updateMode();
            monitor.notifyAll();
        }
    }

    public void setThreshold(long threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Threshold must be positive");
        }

        synchronized (monitor) {
            ensureOpen();
            this.threshold = threshold;
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
            byte[] copy = new byte[size];
            for (int i = 0; i < size; i++) {
                copy[i] = storage[i];
            }
            return new Snapshot(copy, cursor, heat, threshold, mode);
        }
    }

    private void ensureOpen() {
        if (mode == Mode.CLOSED) {
            throw new IllegalStateException("Stream is closed");
        }
    }

    private void updateMode() {
        mode = heat >= threshold ? Mode.OVERHEATED : Mode.NOMINAL;
    }
}
